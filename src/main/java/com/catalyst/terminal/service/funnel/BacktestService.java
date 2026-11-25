package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.config.FunnelProperties;
import com.catalyst.terminal.model.CatalystFilingRecord;
import com.catalyst.terminal.model.Direction;
import com.catalyst.terminal.model.funnel.*;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import com.catalyst.terminal.repository.WatchlistRepository;
import com.catalyst.terminal.service.PipelineMetricsService;
import com.catalyst.terminal.service.dhan.DhanInstrumentService;
import com.catalyst.terminal.service.dhan.DhanMarketGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Zero-lookahead bar-by-bar historical replay backtester using DhanHQ daily and 5-minute intraday candles.
 * Compares generated signals against actual forward price action up to the 3:15 PM IST auto-cutoff.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> DEFAULT_SYMBOLS = List.of(
            "JWL", "RVNL", "BEL", "BDL", "LT", "POWERINDIA", "HDFCBANK", "RELIANCE", "SBIN", "TCS"
    );

    private final FunnelProperties funnelProperties;
    private final DhanInstrumentService instrumentService;
    private final DhanMarketGateway marketGateway;
    private final CatalystFilingRepository filingRepository;
    private final WatchlistRepository watchlistRepository;
    private final TechnicalIndicatorService indicatorService;
    private final SetupGuardrailService guardrailService;
    private final CompositeScorerService scorerService;
    private PipelineMetricsService metricsService;

    @Autowired(required = false)
    public void setMetricsService(PipelineMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public BacktestService(
            FunnelProperties funnelProperties,
            DhanInstrumentService instrumentService,
            DhanMarketGateway marketGateway,
            CatalystFilingRepository filingRepository,
            WatchlistRepository watchlistRepository,
            TechnicalIndicatorService indicatorService,
            SetupGuardrailService guardrailService,
            CompositeScorerService scorerService
    ) {
        this.funnelProperties = funnelProperties;
        this.instrumentService = instrumentService;
        this.marketGateway = marketGateway;
        this.filingRepository = filingRepository;
        this.watchlistRepository = watchlistRepository;
        this.indicatorService = indicatorService;
        this.guardrailService = guardrailService;
        this.scorerService = scorerService;
    }

    public BacktestSummary runBacktest(BacktestRequest req) {
        LocalDate to = req.toDate() != null ? req.toDate() : LocalDate.now(IST);
        LocalDate from = req.fromDate() != null ? req.fromDate() : to.minusDays(7);
        if (from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 45) {
            from = to.minusDays(45);
        }

        double capital = req.capitalInr() != null && req.capitalInr() >= 1000
                ? req.capitalInr() : funnelProperties.getCapitalInr();
        double riskPct = req.riskPct() != null && req.riskPct() > 0 && req.riskPct() <= 10.0
                ? req.riskPct() : funnelProperties.getRiskPerTradePct();
        double minScore = req.minCompositeScore() != null
                ? req.minCompositeScore() : funnelProperties.getMinCompositeScore();

        instrumentService.ensureLoadedLazily();
        List<String> symbols = resolveSymbols(req.symbols(), from, to);
        log.info("Starting historical backtest: {} to {} | symbols={} | minScore={} | assumedCatalyst={}",
                from, to, symbols, minScore, req.assumedCatalystScore());

        // Fetch historical H2 filings in [from - 1, to]
        List<CatalystFilingRecord> historicalFilings = filingRepository.search(
                from.minusDays(1), to, null, null, null, null, null, null, 500
        );

        // Fetch Nifty 50 + sectoral index 5m bars once for the full range
        Set<String> neededIdxIds = new LinkedHashSet<>();
        neededIdxIds.add(DhanInstrumentService.NIFTY_50_SEC_ID);
        for (String sym : symbols) {
            neededIdxIds.add(instrumentService.resolveSectorIndexSecurityId(sym));
        }
        Map<String, Map<Long, Double>> indexBarReturns = fetchIndexBarReturns(neededIdxIds, from, to);
        Map<Long, Double> niftyReturns = indexBarReturns.getOrDefault(DhanInstrumentService.NIFTY_50_SEC_ID, Map.of());

        List<BacktestTradeResult> passedTrades = new ArrayList<>();
        List<BacktestTradeResult> filteredSetups = new ArrayList<>();
        Set<LocalDate> scannedDays = new TreeSet<>();

        for (String sym : symbols) {
            Optional<String> secIdOpt = instrumentService.getSecurityId(sym);
            if (secIdOpt.isEmpty()) continue;
            String secId = secIdOpt.get();

            List<Candle> allDaily = marketGateway.fetchDailyCandles(
                    secId, "NSE_EQ", "EQUITY", from.minusDays(40), to.plusDays(1)
            );
            List<Candle> allIntraday = marketGateway.fetchIntradayCandles(
                    secId, "NSE_EQ", "EQUITY", "5", from, to.plusDays(1)
            );
            if (allDaily.isEmpty() || allIntraday.isEmpty()) continue;

            Map<LocalDate, List<Candle>> byDate = groupByIstDate(allIntraday);
            String sectorName = instrumentService.resolveSectorIndexName(sym);
            String sectorSecId = instrumentService.resolveSectorIndexSecurityId(sym);
            Map<Long, Double> sectorReturns = indexBarReturns.getOrDefault(sectorSecId, niftyReturns);
            boolean inUniverse = instrumentService.isInLiquidUniverse(sym);

            for (Map.Entry<LocalDate, List<Candle>> entry : byDate.entrySet()) {
                LocalDate sessionDate = entry.getKey();
                if (sessionDate.isBefore(from) || sessionDate.isAfter(to)) continue;
                scannedDays.add(sessionDate);

                List<Candle> priorDaily = allDaily.stream()
                        .filter(c -> Instant.ofEpochSecond(c.timestampEpoch()).atZone(IST).toLocalDate().isBefore(sessionDate))
                        .toList();
                if (priorDaily.size() < 2) continue;

                CatalystMatch catMatch = matchCatalyst(sym, sessionDate, historicalFilings, req.assumedCatalystScore());
                SessionReplayOutcome outcome = replaySymbolSession(
                        sym,
                        sectorName,
                        inUniverse,
                        sessionDate,
                        priorDaily,
                        entry.getValue(),
                        niftyReturns,
                        sectorReturns,
                        catMatch.score(),
                        catMatch.side(),
                        catMatch.source(),
                        minScore,
                        capital,
                        riskPct
                );

                if (outcome.passedTrade() != null) {
                    passedTrades.add(outcome.passedTrade());
                } else if (outcome.bestFiltered() != null) {
                    filteredSetups.add(outcome.bestFiltered());
                }
            }
        }

        passedTrades.sort(Comparator.comparing(BacktestTradeResult::sessionDate)
                .thenComparing(BacktestTradeResult::signalTimeIst));
        filteredSetups.sort(Comparator.comparing(BacktestTradeResult::sessionDate)
                .thenComparing(BacktestTradeResult::compositeScore).reversed());

        BacktestSummary summary = buildSummary(from, to, scannedDays.size(), symbols.size(), capital, passedTrades, filteredSetups);
        log.info("Backtest complete ({} to {}): {} day(s), {} passed ({}W/{}L, winRate={}%, netPnl=₹{}, PF={}, avgR={}R), {} filtered ({}% losers blocked)",
                from, to, summary.tradingDaysScanned(), summary.totalPassedSignals(),
                summary.wins(), summary.losses(), summary.winRatePct(), summary.netPnlInr(),
                summary.profitFactor(), summary.avgRMultiple(),
                summary.totalFilteredSignals(), summary.filteredWouldHaveLostPct());
        if (metricsService != null) {
            metricsService.recordEvent("BACKTEST", summary.netPnlInr() >= 0 ? "SUCCESS" : "WARN", "HIST",
                    String.format("%s..%s: %d trades (%s%% win, Net ₹%.0f, PF %s)",
                            from, to, summary.totalPassedSignals(), summary.winRatePct(),
                            summary.netPnlInr(), summary.profitFactor()));
        }
        return summary;
    }

    SessionReplayOutcome replaySymbolSession(
            String symbol,
            String sectorName,
            boolean inLiquidUniverse,
            LocalDate sessionDate,
            List<Candle> priorDaily,
            List<Candle> sessionBars,
            Map<Long, Double> niftyBarReturns,
            Map<Long, Double> sectorBarReturns,
            int catalystScore,
            TradeSide catalystSide,
            String catalystSource,
            double minCompositeScore,
            double capitalInr,
            double riskPct
    ) {
        if (sessionBars == null || sessionBars.size() < 6 || priorDaily == null || priorDaily.isEmpty()) {
            return new SessionReplayOutcome(null, null);
        }

        Candle prevDaily = priorDaily.get(priorDaily.size() - 1);
        double prevClose = prevDaily.close();
        double pdh = prevDaily.high();
        double pdl = prevDaily.low();
        double atr14 = indicatorService.computeAtr14(priorDaily);
        double avg20dVol = indicatorService.computeAvgDailyVolume(priorDaily, 20);
        double avg20dTurnoverCr = indicatorService.computeAvgDailyTurnoverCr(priorDaily, 20);

        double[] orb = indicatorService.computeOpeningRange15m(sessionBars);
        double orh = orb[0];
        double orl = orb[1];

        double cumPv = 0.0;
        long cumVol = 0L;
        double runningHigh = Double.MIN_VALUE;
        double runningLow = Double.MAX_VALUE;

        BacktestTradeResult bestFiltered = null;

        for (int i = 0; i < sessionBars.size(); i++) {
            Candle bar = sessionBars.get(i);
            LocalTime barTime = Instant.ofEpochSecond(bar.timestampEpoch()).atZone(IST).toLocalTime();
            double tp = (bar.high() + bar.low() + bar.close()) / 3.0;
            cumPv += tp * bar.volume();
            cumVol += bar.volume();
            runningHigh = Math.max(runningHigh, bar.high());
            runningLow = Math.min(runningLow, bar.low());

            // Observe 09:15–09:30 without triggering entries; stop entries at 14:30
            if (barTime.isBefore(LocalTime.of(9, 30)) || !barTime.isBefore(LocalTime.of(14, 30))) {
                continue;
            }

            double ltp = bar.close();
            double vwap = cumVol > 0 ? TechnicalIndicatorService.round2(cumPv / cumVol) : ltp;

            TradeSide side = catalystSide;
            if (side == TradeSide.NONE) {
                if (ltp > vwap && ltp >= orh) side = TradeSide.LONG;
                else if (ltp < vwap && ltp <= orl) side = TradeSide.SHORT;
            }
            if (side == TradeSide.NONE) continue;

            List<Candle> barsUpToNow = sessionBars.subList(0, i + 1);
            double rvol = indicatorService.computeTimeNormalizedRvol(cumVol, avg20dVol, barTime);
            double rangePos = indicatorService.computeIntradayRangePosition(ltp, runningHigh, runningLow);
            boolean risingVol = indicatorService.isRisingBreakdownOrBreakoutVolume(barsUpToNow);
            double stock5mRet = bar.open() > 0 ? ((bar.close() - bar.open()) / bar.open()) * 100.0 : 0.0;
            double niftyRet = niftyBarReturns.getOrDefault(bar.timestampEpoch(), 0.0);
            double sectorRet = sectorBarReturns.getOrDefault(bar.timestampEpoch(), niftyRet);

            double swingLow = indicatorService.findRecentSwingLow(barsUpToNow, 6, runningLow);
            double swingHigh = indicatorService.findRecentSwingHigh(barsUpToNow, 6, runningHigh);

            SetupGuardrailService.GuardrailEvaluation guardEval = guardrailService.evaluate(
                    inLiquidUniverse,
                    avg20dTurnoverCr,
                    ltp,
                    prevClose,
                    atr14,
                    vwap,
                    swingLow,
                    swingHigh,
                    pdh,
                    pdl,
                    runningHigh,
                    runningLow,
                    0.04,
                    side,
                    barTime
            );

            ScoreBreakdown breakdown = scorerService.score(
                    catalystScore,
                    rvol,
                    side,
                    ltp,
                    vwap,
                    orh,
                    orl,
                    pdh,
                    pdl,
                    rangePos,
                    risingVol,
                    stock5mRet,
                    niftyRet,
                    sectorRet,
                    avg20dTurnoverCr,
                    0.04,
                    guardEval.atrPenalty()
            );

            ExecutionPlan plan = scalePlan(guardEval.executionPlan(), capitalInr, riskPct);
            if (plan == null) continue;

            boolean passedAll = guardEval.status().passed() && breakdown.compositeTotal() > minCompositeScore;
            if (passedAll) {
                BacktestTradeResult trade = simulateForwardOutcome(
                        sessionDate, symbol, sectorName, side, barTime, breakdown.compositeTotal(),
                        catalystScore, catalystSource, rvol, vwap, orh, orl, atr14,
                        guardEval.status().atrMultiple(), plan, sessionBars, i, true,
                        guardEval.status().disqualificationReasons()
                );
                return new SessionReplayOutcome(trade, null);
            } else if (breakdown.compositeTotal() >= 45.0) {
                BacktestTradeResult filtered = simulateForwardOutcome(
                        sessionDate, symbol, sectorName, side, barTime, breakdown.compositeTotal(),
                        catalystScore, catalystSource, rvol, vwap, orh, orl, atr14,
                        guardEval.status().atrMultiple(), plan, sessionBars, i, false,
                        guardEval.status().disqualificationReasons()
                );
                if (bestFiltered == null || filtered.compositeScore() > bestFiltered.compositeScore()) {
                    bestFiltered = filtered;
                }
            }
        }

        return new SessionReplayOutcome(null, bestFiltered);
    }

    private BacktestTradeResult simulateForwardOutcome(
            LocalDate sessionDate,
            String symbol,
            String sectorName,
            TradeSide side,
            LocalTime signalTime,
            double compositeScore,
            int catalystScore,
            String catalystSource,
            double rvol,
            double vwap,
            double orh,
            double orl,
            double atr14,
            double atrMultiple,
            ExecutionPlan plan,
            List<Candle> sessionBars,
            int entryIndex,
            boolean guardrailsPassed,
            List<String> guardrailReasons
    ) {
        double entry = plan.entry();
        double sl = plan.stopLoss();
        double target = plan.target();
        int qty = Math.max(1, plan.quantity());
        double riskPerShare = plan.riskPerShare();

        String exitTime = signalTime.format(TIME_FMT);
        double exitPrice = entry;
        String outcome = "TIME_EXIT_FLAT";

        double bestFav = entry;
        double worstAdv = entry;
        double eodClose = sessionBars.stream()
                .filter(b -> !Instant.ofEpochSecond(b.timestampEpoch()).atZone(IST).toLocalTime().isAfter(LocalTime.of(15, 15)))
                .reduce((first, second) -> second)
                .map(Candle::close)
                .orElse(sessionBars.get(sessionBars.size() - 1).close());

        boolean exited = false;
        for (int j = entryIndex + 1; j < sessionBars.size(); j++) {
            Candle c = sessionBars.get(j);
            LocalTime t = Instant.ofEpochSecond(c.timestampEpoch()).atZone(IST).toLocalTime();
            if (t.isAfter(LocalTime.of(15, 15))) {
                break;
            }
            if (side == TradeSide.LONG) {
                bestFav = Math.max(bestFav, c.high());
                worstAdv = Math.min(worstAdv, c.low());
                // Conservative: check stop-loss before target if both fall within same 5m bar
                if (c.low() <= sl) {
                    exitTime = t.format(TIME_FMT);
                    exitPrice = sl;
                    outcome = "SL_HIT";
                    exited = true;
                    break;
                }
                if (c.high() >= target) {
                    exitTime = t.format(TIME_FMT);
                    exitPrice = target;
                    outcome = "TARGET_HIT";
                    exited = true;
                    break;
                }
            } else {
                bestFav = Math.min(bestFav, c.low());
                worstAdv = Math.max(worstAdv, c.high());
                if (c.high() >= sl) {
                    exitTime = t.format(TIME_FMT);
                    exitPrice = sl;
                    outcome = "SL_HIT";
                    exited = true;
                    break;
                }
                if (c.low() <= target) {
                    exitTime = t.format(TIME_FMT);
                    exitPrice = target;
                    outcome = "TARGET_HIT";
                    exited = true;
                    break;
                }
            }
            exitTime = t.format(TIME_FMT);
            exitPrice = c.close();
        }

        double pnlPerShare = side == TradeSide.LONG ? (exitPrice - entry) : (entry - exitPrice);
        double pnlInr = TechnicalIndicatorService.round2(pnlPerShare * qty);
        double rRealized = riskPerShare > 0 ? TechnicalIndicatorService.round2(pnlPerShare / riskPerShare) : 0.0;
        if (!exited) {
            outcome = pnlInr > 0 ? "TIME_EXIT_WIN" : (pnlInr < 0 ? "TIME_EXIT_LOSS" : "TIME_EXIT_FLAT");
        }

        double mfePct = side == TradeSide.LONG
                ? TechnicalIndicatorService.round2(((bestFav - entry) / entry) * 100.0)
                : TechnicalIndicatorService.round2(((entry - bestFav) / entry) * 100.0);
        double maePct = side == TradeSide.LONG
                ? TechnicalIndicatorService.round2(((worstAdv - entry) / entry) * 100.0)
                : TechnicalIndicatorService.round2(((entry - worstAdv) / entry) * 100.0);

        List<String> reasons = new ArrayList<>(guardrailReasons != null ? guardrailReasons : List.of());
        if (guardrailsPassed && reasons.isEmpty() && compositeScore <= funnelProperties.getMinCompositeScore()) {
            reasons.add("Composite score below threshold");
        }

        return new BacktestTradeResult(
                sessionDate,
                symbol,
                sectorName,
                side,
                signalTime.format(TIME_FMT),
                compositeScore,
                catalystScore,
                catalystSource,
                rvol,
                vwap,
                orh,
                orl,
                atr14,
                atrMultiple,
                entry,
                sl,
                target,
                qty,
                plan.rrRatio(),
                exitTime,
                TechnicalIndicatorService.round2(exitPrice),
                outcome,
                pnlInr,
                rRealized,
                mfePct,
                maePct,
                TechnicalIndicatorService.round2(eodClose),
                guardrailsPassed,
                List.copyOf(reasons)
        );
    }

    private BacktestSummary buildSummary(
            LocalDate from,
            LocalDate to,
            int tradingDays,
            int symbolsCount,
            double capitalInr,
            List<BacktestTradeResult> passed,
            List<BacktestTradeResult> filtered
    ) {
        int wins = 0, losses = 0, targets = 0, sls = 0, timeExits = 0;
        double grossProfit = 0.0, grossLoss = 0.0, netPnl = 0.0, sumR = 0.0;
        double equity = 0.0, peak = 0.0, maxDd = 0.0;

        for (BacktestTradeResult t : passed) {
            netPnl += t.pnlInr();
            sumR += t.rMultipleRealized();
            if ("TARGET_HIT".equals(t.outcome())) targets++;
            else if ("SL_HIT".equals(t.outcome())) sls++;
            else timeExits++;

            if (t.pnlInr() > 0) {
                wins++;
                grossProfit += t.pnlInr();
            } else if (t.pnlInr() < 0) {
                losses++;
                grossLoss += Math.abs(t.pnlInr());
            }
            equity += t.pnlInr();
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }

        int n = passed.size();
        double winRate = n > 0 ? TechnicalIndicatorService.round2((wins * 100.0) / n) : 0.0;
        double retPct = capitalInr > 0 ? TechnicalIndicatorService.round2((netPnl / capitalInr) * 100.0) : 0.0;
        double pf = grossLoss > 0 ? TechnicalIndicatorService.round2(grossProfit / grossLoss)
                : (grossProfit > 0 ? 99.9 : 0.0);
        double avgR = n > 0 ? TechnicalIndicatorService.round2(sumR / n) : 0.0;

        long filteredLosers = filtered.stream().filter(t -> t.pnlInr() < 0).count();
        double filteredLostPct = !filtered.isEmpty()
                ? TechnicalIndicatorService.round2((filteredLosers * 100.0) / filtered.size()) : 0.0;

        return new BacktestSummary(
                from,
                to,
                tradingDays,
                symbolsCount,
                n,
                filtered.size(),
                wins,
                losses,
                targets,
                sls,
                timeExits,
                winRate,
                TechnicalIndicatorService.round2(netPnl),
                retPct,
                pf,
                avgR,
                TechnicalIndicatorService.round2(maxDd),
                filteredLostPct,
                passed,
                filtered
        );
    }

    private ExecutionPlan scalePlan(ExecutionPlan base, double capitalInr, double riskPct) {
        if (base == null || base.riskPerShare() <= 0) return null;
        double riskInr = TechnicalIndicatorService.round2(capitalInr * (riskPct / 100.0));
        int qty = (int) Math.floor(riskInr / base.riskPerShare());
        return new ExecutionPlan(
                base.side(), base.entry(), base.stopLoss(), base.target(),
                qty, riskInr, base.riskPerShare(), base.rewardPerShare(), base.rrRatio()
        );
    }

    private CatalystMatch matchCatalyst(
            String symbol,
            LocalDate sessionDate,
            List<CatalystFilingRecord> filings,
            Integer assumedCatalystScore
    ) {
        CatalystFilingRecord best = null;
        for (CatalystFilingRecord r : filings) {
            if (!symbol.equalsIgnoreCase(r.symbol()) || r.evaluatedAt() == null) continue;
            LocalDate fd = r.evaluatedAt().toLocalDate();
            if (fd.equals(sessionDate) || fd.equals(sessionDate.minusDays(1))) {
                if (best == null || r.catalystScore() > best.catalystScore()) {
                    best = r;
                }
            }
        }
        if (best != null && best.catalystScore() > 0) {
            TradeSide side = best.direction() == Direction.BULLISH ? TradeSide.LONG
                    : (best.direction() == Direction.BEARISH ? TradeSide.SHORT : TradeSide.NONE);
            return new CatalystMatch(best.catalystScore(), side, best.sourceMode());
        }
        if (assumedCatalystScore != null && assumedCatalystScore > 0) {
            return new CatalystMatch(Math.min(100, assumedCatalystScore), TradeSide.NONE, "SIMULATED_CATALYST");
        }
        return new CatalystMatch(0, TradeSide.NONE, "PURE_TECHNICAL");
    }

    private Map<String, Map<Long, Double>> fetchIndexBarReturns(Set<String> idxIds, LocalDate from, LocalDate to) {
        Map<String, Map<Long, Double>> out = new HashMap<>();
        for (String idxId : idxIds) {
            List<Candle> bars = marketGateway.fetchIntradayCandles(idxId, "IDX_I", "INDEX", "5", from, to.plusDays(1));
            Map<Long, Double> retMap = new HashMap<>();
            for (Candle b : bars) {
                double ret = b.open() > 0 ? ((b.close() - b.open()) / b.open()) * 100.0 : 0.0;
                retMap.put(b.timestampEpoch(), ret);
            }
            out.put(idxId, retMap);
        }
        return out;
    }

    private Map<LocalDate, List<Candle>> groupByIstDate(List<Candle> candles) {
        Map<LocalDate, List<Candle>> grouped = new TreeMap<>();
        for (Candle c : candles) {
            LocalDate d = Instant.ofEpochSecond(c.timestampEpoch()).atZone(IST).toLocalDate();
            grouped.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
        }
        return grouped;
    }

    private List<String> resolveSymbols(List<String> requested, LocalDate from, LocalDate to) {
        Set<String> set = new LinkedHashSet<>();
        if (requested != null) {
            for (String s : requested) {
                if (s != null && !s.isBlank()) {
                    set.add(s.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (set.isEmpty()) {
            for (CatalystFilingRecord r : filingRepository.search(from.minusDays(1), to, null, 40, null, null, null, null, 20)) {
                set.add(r.symbol().toUpperCase(Locale.ROOT));
            }
            for (var w : watchlistRepository.findAll()) {
                set.add(w.symbol().toUpperCase(Locale.ROOT));
            }
            set.addAll(DEFAULT_SYMBOLS);
        }
        return set.stream().limit(12).toList();
    }

    private record CatalystMatch(int score, TradeSide side, String source) {}

    public record SessionReplayOutcome(BacktestTradeResult passedTrade, BacktestTradeResult bestFiltered) {}
}

