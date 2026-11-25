package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.config.FunnelProperties;
import com.catalyst.terminal.gateway.BseFeedGateway;
import com.catalyst.terminal.gateway.NsePreOpenGateway;
import com.catalyst.terminal.model.CatalystFilingRecord;
import com.catalyst.terminal.model.Direction;
import com.catalyst.terminal.model.funnel.*;
import com.catalyst.terminal.repository.CatalystFilingRepository;
import com.catalyst.terminal.repository.FunnelSetupRepository;
import com.catalyst.terminal.repository.WatchlistRepository;
import com.catalyst.terminal.service.*;
import com.catalyst.terminal.service.dhan.DhanAuthService;
import com.catalyst.terminal.service.dhan.DhanInstrumentService;
import com.catalyst.terminal.service.dhan.DhanMarketGateway;
import com.catalyst.terminal.service.dhan.DhanWebSocketFeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates the 3-Stage Intraday Funnel (8:00 AM to 3:15 PM IST):
 * 1. Catalyst Sweep (NSE + BSE overnight & live filings)
 * 2. Pre-Market Filter (9:07–9:14 AM IST gap & volume filter)
 * 3. Observation & Range Lock (9:15–9:30 AM) -> Live Probability Ranker (9:30–2:30 PM) -> 3:15 PM Auto-Cutoff.
 */
@Service
public class DailyFunnelOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DailyFunnelOrchestrator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final FunnelProperties properties;
    private final DhanAuthService dhanAuthService;
    private final DhanInstrumentService instrumentService;
    private final DhanMarketGateway marketGateway;
    private final DhanWebSocketFeedService webSocketFeed;
    private final BseFeedGateway bseFeedGateway;
    private final NsePreOpenGateway nsePreOpenGateway;
    private final CatalystFirewall firewall;
    private final CatalystEvaluatorService evaluatorService;
    private final CatalystFilingRepository filingRepository;
    private final FunnelSetupRepository funnelSetupRepository;
    private final WatchlistRepository watchlistRepository;
    private final TechnicalIndicatorService indicatorService;
    private final SetupGuardrailService guardrailService;
    private final CompositeScorerService scorerService;
    private final PipelineMetricsService metricsService;
    private final SseBroadcastService sseBroadcast;
    private final TelegramAlertService telegramAlertService;

    private final Map<String, FunnelCandidate> candidates = new ConcurrentHashMap<>();
    private final Set<String> seenBseUids = ConcurrentHashMap.newKeySet();
    private final Set<String> alertedToday = ConcurrentHashMap.newKeySet();
    private final AtomicReference<List<FunnelSetupRecord>> topSetups = new AtomicReference<>(List.of());
    private final AtomicReference<List<FunnelSetupRecord>> allEvaluated = new AtomicReference<>(List.of());
    private final AtomicReference<LocalDateTime> lastScanAt = new AtomicReference<>();
    private final AtomicReference<LocalDate> cutoffSentDate = new AtomicReference<>();
    private final AtomicReference<LocalDate> rangeLockedDate = new AtomicReference<>();

    public DailyFunnelOrchestrator(
            FunnelProperties properties,
            DhanAuthService dhanAuthService,
            DhanInstrumentService instrumentService,
            DhanMarketGateway marketGateway,
            DhanWebSocketFeedService webSocketFeed,
            BseFeedGateway bseFeedGateway,
            NsePreOpenGateway nsePreOpenGateway,
            CatalystFirewall firewall,
            CatalystEvaluatorService evaluatorService,
            CatalystFilingRepository filingRepository,
            FunnelSetupRepository funnelSetupRepository,
            WatchlistRepository watchlistRepository,
            TechnicalIndicatorService indicatorService,
            SetupGuardrailService guardrailService,
            CompositeScorerService scorerService,
            PipelineMetricsService metricsService,
            SseBroadcastService sseBroadcast,
            TelegramAlertService telegramAlertService
    ) {
        this.properties = properties;
        this.dhanAuthService = dhanAuthService;
        this.instrumentService = instrumentService;
        this.marketGateway = marketGateway;
        this.webSocketFeed = webSocketFeed;
        this.bseFeedGateway = bseFeedGateway;
        this.nsePreOpenGateway = nsePreOpenGateway;
        this.firewall = firewall;
        this.evaluatorService = evaluatorService;
        this.filingRepository = filingRepository;
        this.funnelSetupRepository = funnelSetupRepository;
        this.watchlistRepository = watchlistRepository;
        this.indicatorService = indicatorService;
        this.guardrailService = guardrailService;
        this.scorerService = scorerService;
        this.metricsService = metricsService;
        this.sseBroadcast = sseBroadcast;
        this.telegramAlertService = telegramAlertService;
    }

    public FunnelStage getCurrentStage() {
        return resolveStage(LocalTime.now(IST));
    }

    public FunnelStage resolveStage(LocalTime now) {
        if (!now.isBefore(LocalTime.of(8, 0)) && now.isBefore(LocalTime.of(9, 7))) {
            return FunnelStage.OVERNIGHT_CATALYST;
        }
        if (!now.isBefore(LocalTime.of(9, 7)) && now.isBefore(LocalTime.of(9, 15))) {
            return FunnelStage.PRE_MARKET_FILTER;
        }
        if (!now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(9, 30))) {
            return FunnelStage.OPENING_RANGE_LOCK;
        }
        if (!now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(14, 30))) {
            return FunnelStage.LIVE_RANKER;
        }
        if (!now.isBefore(LocalTime.of(15, 15)) && now.isBefore(LocalTime.of(15, 35))) {
            return FunnelStage.AUTO_CUTOFF;
        }
        return FunnelStage.AFTER_HOURS;
    }

    @EventListener
    public void onCatalystScored(CatalystFilingRecord record) {
        registerCatalystFiling(record);
    }

    public void registerCatalystFiling(CatalystFilingRecord record) {
        if (record == null || record.symbol() == null || record.symbol().isBlank() || record.catalystScore() < 40) {
            return;
        }
        String sym = record.symbol().trim().toUpperCase(Locale.ROOT);
        TradeSide side = record.direction() == Direction.BULLISH ? TradeSide.LONG
                : (record.direction() == Direction.BEARISH ? TradeSide.SHORT : TradeSide.NONE);
        candidates.compute(sym, (k, existing) -> {
            if (existing == null || record.catalystScore() >= existing.catalystScore()) {
                return new FunnelCandidate(
                        sym,
                        record.companyName(),
                        record.catalystScore(),
                        side,
                        record.sourceMode(),
                        record.headlineSummary() != null ? record.headlineSummary() : record.rawHeadline(),
                        0.0
                );
            }
            return existing;
        });
    }

    /**
     * 8:00–9:00 AM IST: Sweeps yesterday's post-3:30 PM & morning BSE/NSE announcements.
     */
    @Scheduled(cron = "0 0/10 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMorningCatalystSweep() {
        if (!properties.isEnabled() || !NseCalendarUtil.isTradingDay(LocalDate.now(IST))) return;
        seedCandidatesFromArchiveAndWatchlist();
        sweepBseAnnouncements(LocalDate.now(IST).minusDays(1), LocalDate.now(IST));
        metricsService.recordEvent("FUNNEL", "INFO", "STAGE1",
                "Morning catalyst sweep complete: " + candidates.size() + " candidate(s) queued.");
    }

    /**
     * 9:07–9:14 AM IST: Pre-Market Filter for Nifty 500 / F&O stocks gapping 1.5%–4.0%.
     */
    @Scheduled(cron = "0 7-14/2 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPreMarketFilter() {
        if (!properties.isEnabled() || !NseCalendarUtil.isTradingDay(LocalDate.now(IST))) return;
        instrumentService.ensureLoadedLazily();
        List<NsePreOpenGateway.PreOpenCandidate> preOpen = nsePreOpenGateway.fetchPreOpenCandidates();
        int flagged = 0;
        for (NsePreOpenGateway.PreOpenCandidate p : preOpen) {
            double absGap = Math.abs(p.gapPct());
            if (instrumentService.isInLiquidUniverse(p.symbol())
                    && absGap >= 1.5 && absGap <= 4.0
                    && p.preOpenVolume() > 0) {
                TradeSide side = p.gapPct() > 0 ? TradeSide.LONG : TradeSide.SHORT;
                String reason = String.format("Pre-open gap %.2f%% (vol %,d)", p.gapPct(), p.preOpenVolume());
                candidates.putIfAbsent(p.symbol(), new FunnelCandidate(
                        p.symbol(), p.symbol(), 50, side, "PRE_OPEN", reason, p.gapPct()
                ));
                flagged++;
            }
        }
        metricsService.recordEvent("FUNNEL", "INFO", "STAGE2",
                "Pre-market filter flagged " + flagged + " liquid gapper(s); total pool: " + candidates.size());
    }

    /**
     * 9:15–9:30 AM IST: Subscribe Dhan WebSocket and observe 15m Opening Range without triggering entries.
     */
    @Scheduled(cron = "0 15,20,25 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runOpeningRangeObservation() {
        if (!properties.isEnabled() || !NseCalendarUtil.isTradingDay(LocalDate.now(IST))) return;
        seedCandidatesFromArchiveAndWatchlist();
        subscribeCandidatesOnWebSocket();
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(rangeLockedDate.get())) {
            rangeLockedDate.set(today);
            metricsService.recordEvent("FUNNEL", "INFO", "STAGE3",
                    "9:15–9:30 AM Observation active: locking 15m ORH/ORL & VWAP (entries paused).");
        }
    }

    /**
     * 9:30 AM – 2:30 PM IST: Live Probability Ranker every 1–5 min.
     */
    @Scheduled(fixedDelayString = "${catalyst.funnel.rank-interval-ms:60000}")
    public void scheduledLiveRanker() {
        if (!properties.isEnabled() || !NseCalendarUtil.isTradingDay(LocalDate.now(IST))) return;
        FunnelStage stage = getCurrentStage();
        if (stage == FunnelStage.LIVE_RANKER) {
            runFullRankScan(false);
        }
    }

    /**
     * 3:15 PM IST Auto-Cutoff before 3:20 PM broker square-off.
     */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void triggerAutoCutoff() {
        if (!properties.isEnabled() || !NseCalendarUtil.isTradingDay(LocalDate.now(IST))) return;
        LocalDate today = LocalDate.now(IST);
        if (today.equals(cutoffSentDate.getAndSet(today))) {
            return;
        }
        metricsService.recordEvent("FUNNEL", "WARN", "CUTOFF",
                "3:15 PM Auto-Cutoff reached: halting new intraday signals. Square off open positions before 3:20 PM.");
        sseBroadcast.broadcastEvent("funnel_cutoff", Map.of(
                "timeIst", LocalTime.now(IST).toString(),
                "message", "3:15 PM Auto-Cutoff: No new entries. Square off before 3:20 PM broker window."
        ));
        telegramAlertService.sendRawHtmlAlert(
                "⏰ <b>3:15 PM Auto-Cutoff</b>\nAll new intraday signals halted. Please square off open intraday positions before the 3:20 PM broker auto-square-off window."
        );
    }

    /**
     * Executes a full Stage 1 -> Stage 2 -> Stage 3 scan & rank pass (can also be triggered on-demand).
     */
    public synchronized Map<String, Object> runFullRankScan(boolean includeMarketSourcesNow) {
        instrumentService.ensureLoadedLazily();
        seedCandidatesFromArchiveAndWatchlist();
        if (includeMarketSourcesNow) {
            sweepBseAnnouncements(LocalDate.now(IST).minusDays(1), LocalDate.now(IST));
        }
        subscribeCandidatesOnWebSocket();

        LocalTime nowIst = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);
        List<FunnelCandidate> pool = new ArrayList<>(candidates.values());

        if (pool.isEmpty()) {
            topSetups.set(List.of());
            allEvaluated.set(List.of());
            lastScanAt.set(LocalDateTime.now(IST));
            return getSnapshot();
        }

        // Batch-fetch quotes for all candidate securityIds + Nifty 50 + sectoral indices
        Map<String, String> symToSec = new LinkedHashMap<>();
        for (FunnelCandidate c : pool) {
            instrumentService.getSecurityId(c.symbol()).ifPresent(secId -> symToSec.put(c.symbol(), secId));
        }

        List<String> eqIds = new ArrayList<>(symToSec.values());
        List<String> idxIds = new ArrayList<>(instrumentService.getAllTrackedIndexIds());
        Map<String, DhanQuote> restQuotes = marketGateway.fetchQuotes(eqIds, idxIds);

        // Fetch 5-min intraday candles only for Nifty 50 + sectoral indices needed by active candidates
        Set<String> neededIdxIds = new LinkedHashSet<>();
        neededIdxIds.add(DhanInstrumentService.NIFTY_50_SEC_ID);
        for (FunnelCandidate c : pool) {
            neededIdxIds.add(instrumentService.resolveSectorIndexSecurityId(c.symbol()));
        }
        Map<String, Double> index5mReturns = computeIndex5mReturns(today, neededIdxIds);
        double nifty5mRet = index5mReturns.getOrDefault(DhanInstrumentService.NIFTY_50_SEC_ID, 0.0);

        List<FunnelSetupRecord> evaluated = new ArrayList<>();
        for (FunnelCandidate cand : pool) {
            String secId = symToSec.get(cand.symbol());
            if (secId == null) continue;

            DhanQuote quote = webSocketFeed.getLiveQuote(secId).orElse(restQuotes.get(secId));
            List<Candle> daily = marketGateway.fetchDailyCandles(
                    secId, "NSE_EQ", "EQUITY", today.minusDays(45), today
            );
            List<Candle> ws5m = webSocketFeed.getToday5mCandles(secId);
            List<Candle> intradayAll = ws5m.size() >= 3
                    ? ws5m
                    : marketGateway.fetchIntradayCandles(
                            secId, "NSE_EQ", "EQUITY", "5", today.minusDays(5), today
                    );

            if (quote == null && daily.isEmpty() && intradayAll.isEmpty()) {
                continue;
            }

            LocalDate sessionDate = resolveLatestSessionDate(intradayAll, today);
            List<Candle> intradaySession = ws5m.size() >= 3
                    ? ws5m
                    : indicatorService.filterSessionDate(intradayAll, sessionDate);

            Candle latestDaily = daily.isEmpty() ? null : daily.get(daily.size() - 1);
            Candle prevDaily = findPreviousSessionDailyCandle(daily, sessionDate);

            double ltp = quote != null && quote.ltp() > 0 ? quote.ltp()
                    : (!intradaySession.isEmpty() ? intradaySession.get(intradaySession.size() - 1).close()
                    : (latestDaily != null ? latestDaily.close() : 0.0));
            if (ltp <= 0) continue;

            double prevClose = prevDaily != null && prevDaily.close() > 0 ? prevDaily.close()
                    : (quote != null && quote.prevClose() > 0 ? quote.prevClose() : ltp);
            double dayHigh = quote != null && quote.high() > 0 ? quote.high()
                    : intradaySession.stream().mapToDouble(Candle::high).max().orElse(ltp);
            double dayLow = quote != null && quote.low() > 0 ? quote.low()
                    : intradaySession.stream().mapToDouble(Candle::low).min().orElse(ltp);
            long cumVol = quote != null && quote.volume() > 0 ? quote.volume()
                    : intradaySession.stream().mapToLong(Candle::volume).sum();
            double atp = quote != null && quote.atp() > 0 ? quote.atp() : ltp;
            double spreadPct = quote != null ? quote.spreadPct() : 0.0;

            double atr14 = indicatorService.computeAtr14(daily);
            double avg20dVol = indicatorService.computeAvgDailyVolume(daily, 20);
            double avg20dTurnoverCr = indicatorService.computeAvgDailyTurnoverCr(daily, 20);
            double pdh = prevDaily != null ? prevDaily.high() : dayHigh;
            double pdl = prevDaily != null ? prevDaily.low() : dayLow;

            double[] orRange = indicatorService.computeOpeningRange15m(intradaySession);
            double orh = orRange[0];
            double orl = orRange[1];
            double vwap = indicatorService.computeVwap(intradaySession, atp);
            double rvol = indicatorService.computeTimeNormalizedRvol(
                    cumVol, avg20dVol, includeMarketSourcesNow && nowIst.isAfter(LocalTime.of(15, 30))
                            ? LocalTime.of(15, 30) : nowIst
            );
            double rangePos = indicatorService.computeIntradayRangePosition(ltp, dayHigh, dayLow);
            boolean risingVol = indicatorService.isRisingBreakdownOrBreakoutVolume(intradaySession);

            TradeSide side = cand.side();
            if (side == TradeSide.NONE) {
                if (vwap > 0 && ltp > vwap && ltp >= orh) side = TradeSide.LONG;
                else if (vwap > 0 && ltp < vwap && ltp <= orl) side = TradeSide.SHORT;
            }

            double stock5mRet = indicatorService.computeRecentBarReturnPct(intradaySession, 1);
            String sectorIdxName = instrumentService.resolveSectorIndexName(cand.symbol());
            String sectorSecId = instrumentService.resolveSectorIndexSecurityId(cand.symbol());
            double sector5mRet = index5mReturns.getOrDefault(sectorSecId, nifty5mRet);

            double swingLow = indicatorService.findRecentSwingLow(intradaySession, 6, dayLow);
            double swingHigh = indicatorService.findRecentSwingHigh(intradaySession, 6, dayHigh);

            SetupGuardrailService.GuardrailEvaluation guardEval = guardrailService.evaluate(
                    instrumentService.isInLiquidUniverse(cand.symbol()),
                    avg20dTurnoverCr,
                    ltp,
                    prevClose,
                    atr14,
                    vwap,
                    swingLow,
                    swingHigh,
                    pdh,
                    pdl,
                    dayHigh,
                    dayLow,
                    spreadPct,
                    side,
                    nowIst
            );

            ScoreBreakdown breakdown = scorerService.score(
                    cand.catalystScore(),
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
                    nifty5mRet,
                    sector5mRet,
                    avg20dTurnoverCr,
                    spreadPct,
                    guardEval.atrPenalty()
            );

            double dayChgPct = prevClose > 0 ? TechnicalIndicatorService.round2(((ltp - prevClose) / prevClose) * 100.0) : 0.0;
            String trigger = buildTechnicalTrigger(side, ltp, vwap, orh, orl, rvol);

            evaluated.add(new FunnelSetupRecord(
                    cand.symbol(),
                    secId,
                    cand.companyName(),
                    sectorIdxName,
                    side,
                    breakdown.compositeTotal(),
                    cand.catalystScore(),
                    cand.source(),
                    cand.whyMoving(),
                    trigger,
                    TechnicalIndicatorService.round2(ltp),
                    dayChgPct,
                    vwap,
                    orh,
                    orl,
                    TechnicalIndicatorService.round2(pdh),
                    TechnicalIndicatorService.round2(pdl),
                    rvol,
                    TechnicalIndicatorService.round2(stock5mRet - nifty5mRet),
                    TechnicalIndicatorService.round2(stock5mRet - sector5mRet),
                    breakdown,
                    guardEval.status(),
                    guardEval.executionPlan(),
                    LocalDateTime.now(IST)
            ));
        }

        evaluated.sort(Comparator.comparingDouble(FunnelSetupRecord::compositeScore).reversed());
        List<FunnelSetupRecord> top = evaluated.stream()
                .filter(s -> s.guardrails().passed() && s.compositeScore() > properties.getMinCompositeScore())
                .limit(properties.getMaxRankedSetups())
                .toList();

        allEvaluated.set(evaluated);
        topSetups.set(top);
        lastScanAt.set(LocalDateTime.now(IST));

        funnelSetupRepository.saveSnapshot(includeMarketSourcesNow ? evaluated : top);
        sseBroadcast.broadcastEvent("funnel_update", getSnapshot());
        alertNewTopSetups(top);

        metricsService.recordEvent("FUNNEL", top.isEmpty() ? "INFO" : "SUCCESS", "RANKER",
                String.format("Funnel scan evaluated %d candidate(s): %d passed (> %.0f score & all guardrails).",
                        evaluated.size(), top.size(), properties.getMinCompositeScore()));

        return getSnapshot();
    }

    public Map<String, Object> getSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stage", getCurrentStage().name());
        out.put("stageWindow", getCurrentStage().getWindowLabel());
        out.put("stageDescription", getCurrentStage().getDescription());
        out.put("dhanConfigured", dhanAuthService.isConfigured());
        out.put("dhanAuthStatus", dhanAuthService.getLastAuthStatus());
        out.put("dhanWsConnected", webSocketFeed.isConnected());
        out.put("nifty500Count", instrumentService.getNifty500Count());
        out.put("mappedSecuritiesCount", instrumentService.getMappedSymbolCount());
        out.put("candidateCount", candidates.size());
        out.put("minCompositeScore", properties.getMinCompositeScore());
        out.put("capitalInr", properties.getCapitalInr());
        out.put("riskPerTradePct", properties.getRiskPerTradePct());
        out.put("lastScanAt", lastScanAt.get() != null ? lastScanAt.get().toString() : null);
        out.put("topSetups", topSetups.get());
        out.put("allEvaluated", allEvaluated.get());
        return out;
    }

    private void seedCandidatesFromArchiveAndWatchlist() {
        LocalDate from = LocalDate.now(IST).minusDays(2);
        List<CatalystFilingRecord> recent = filingRepository.search(
                from, LocalDate.now(IST), null, 40, null, null, null, null, 50
        );
        for (CatalystFilingRecord r : recent) {
            registerCatalystFiling(r);
        }
        for (var w : watchlistRepository.findAll()) {
            String sym = w.symbol().toUpperCase(Locale.ROOT);
            candidates.putIfAbsent(sym, new FunnelCandidate(
                    sym, sym, 40, TradeSide.NONE, "WATCHLIST", "Pinned Watchlist symbol", 0.0
            ));
        }
    }

    private void sweepBseAnnouncements(LocalDate from, LocalDate to) {
        List<BseFeedGateway.BseAnnouncement> items = bseFeedGateway.fetchAnnouncements(from, to);
        int accepted = 0;
        for (BseFeedGateway.BseAnnouncement item : items) {
            if (!seenBseUids.add(item.uid())) continue;
            if (!firewall.evaluate(item.companyName(), item.headline()).pass()) continue;
            String sym = instrumentService.resolveSymbolFromCompanyName(item.companyName());
            if (sym.isBlank() || !instrumentService.isInLiquidUniverse(sym)) continue;
            evaluatorService.processLiveFiling(item.uid(), sym, item.companyName(), item.headline(), item.attachmentUrl());
            accepted++;
            if (accepted >= 10) break;
        }
    }

    private void subscribeCandidatesOnWebSocket() {
        if (!dhanAuthService.isConfigured()) return;
        List<String> eqIds = new ArrayList<>();
        for (FunnelCandidate c : candidates.values()) {
            instrumentService.getSecurityId(c.symbol()).ifPresent(eqIds::add);
        }
        if (!eqIds.isEmpty()) {
            webSocketFeed.connectAndSubscribe(eqIds, instrumentService.getAllTrackedIndexIds());
        }
    }

    private Map<String, Double> computeIndex5mReturns(LocalDate today, Set<String> idxIds) {
        Map<String, Double> returns = new HashMap<>();
        for (String idxId : idxIds) {
            List<Candle> wsIdx = webSocketFeed.getToday5mCandles(idxId);
            if (wsIdx.size() >= 2) {
                returns.put(idxId, indicatorService.computeRecentBarReturnPct(wsIdx, 1));
                continue;
            }
            List<Candle> idxCandles = marketGateway.fetchIntradayCandles(
                    idxId, "IDX_I", "INDEX", "5", today.minusDays(4), today
            );
            if (!idxCandles.isEmpty()) {
                LocalDate session = resolveLatestSessionDate(idxCandles, today);
                List<Candle> sessionBars = indicatorService.filterSessionDate(idxCandles, session);
                returns.put(idxId, indicatorService.computeRecentBarReturnPct(sessionBars, 1));
            }
        }
        return returns;
    }

    private Candle findPreviousSessionDailyCandle(List<Candle> daily, LocalDate sessionDate) {
        if (daily == null || daily.isEmpty()) return null;
        for (int i = daily.size() - 1; i >= 0; i--) {
            LocalDate d = ZonedDateTime.ofInstant(Instant.ofEpochSecond(daily.get(i).timestampEpoch()), IST).toLocalDate();
            if (d.isBefore(sessionDate)) {
                return daily.get(i);
            }
        }
        return daily.size() >= 2 ? daily.get(daily.size() - 2) : daily.get(daily.size() - 1);
    }

    private LocalDate resolveLatestSessionDate(List<Candle> candles, LocalDate fallback) {
        if (candles == null || candles.isEmpty()) return fallback;
        long lastTs = candles.get(candles.size() - 1).timestampEpoch();
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastTs), IST).toLocalDate();
    }

    private String buildTechnicalTrigger(TradeSide side, double ltp, double vwap, double orh, double orl, double rvol) {
        if (side == TradeSide.NONE) {
            return String.format("RVOL %.2fx | VWAP ₹%.2f (No directional breakout)", rvol, vwap);
        }
        double vwapDiffPct = vwap > 0 ? ((ltp - vwap) / vwap) * 100.0 : 0.0;
        if (side == TradeSide.LONG) {
            String brk = (orh > 0 && ltp >= orh) ? "Broke 15m ORH" : "Holding above ORL";
            return String.format("%s with %.2fx RVOL; %.2f%% vs VWAP (₹%.2f)", brk, rvol, vwapDiffPct, vwap);
        } else {
            String brk = (orl > 0 && ltp <= orl) ? "Broke 15m ORL" : "Below ORH";
            return String.format("%s with %.2fx RVOL; %.2f%% vs VWAP (₹%.2f)", brk, rvol, vwapDiffPct, vwap);
        }
    }

    private void alertNewTopSetups(List<FunnelSetupRecord> top) {
        for (FunnelSetupRecord s : top) {
            String key = LocalDate.now(IST) + "_" + s.symbol() + "_" + s.side();
            if (alertedToday.add(key)) {
                ExecutionPlan p = s.executionPlan();
                String html = String.format("""
                        🎯 <b>%s — %s (Composite %.1f/100)</b>
                        • <b>News:</b> %s
                        • <b>Trigger:</b> %s
                        • <b>Plan:</b> Entry ₹%.2f | SL ₹%.2f | Target ₹%.2f (1:%.1f) | Qty <b>%d</b>
                        """,
                        s.symbol(), s.side().name(), s.compositeScore(),
                        s.whyMoving(), s.technicalTrigger(),
                        p != null ? p.entry() : s.ltp(),
                        p != null ? p.stopLoss() : 0.0,
                        p != null ? p.target() : 0.0,
                        p != null ? p.rrRatio() : 0.0,
                        p != null ? p.quantity() : 0
                );
                telegramAlertService.sendRawHtmlAlert(html);
            }
        }
    }

    private record FunnelCandidate(
            String symbol,
            String companyName,
            int catalystScore,
            TradeSide side,
            String source,
            String whyMoving,
            double preOpenGapPct
    ) {}
}
