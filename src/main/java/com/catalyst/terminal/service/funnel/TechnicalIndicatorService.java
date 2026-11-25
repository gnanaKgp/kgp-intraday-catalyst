package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.model.funnel.Candle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Computes deterministic technical indicators from DhanHQ daily and intraday candles:
 * 14-day ATR, 20-day avg turnover/volume, time-normalized RVOL, 15m ORH/ORL, VWAP, PDH/PDL,
 * 5m Relative Strength, and swing support/resistance pivots.
 */
@Service
public class TechnicalIndicatorService {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    public double computeAtr14(List<Candle> dailyCandles) {
        if (dailyCandles == null || dailyCandles.size() < 2) {
            return 0.0;
        }
        int start = Math.max(1, dailyCandles.size() - 14);
        double sumTr = 0.0;
        int count = 0;
        for (int i = start; i < dailyCandles.size(); i++) {
            Candle cur = dailyCandles.get(i);
            Candle prev = dailyCandles.get(i - 1);
            double tr = Math.max(
                    cur.high() - cur.low(),
                    Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close()))
            );
            sumTr += tr;
            count++;
        }
        return count > 0 ? round2(sumTr / count) : 0.0;
    }

    public double computeAvgDailyVolume(List<Candle> dailyCandles, int lookbackDays) {
        if (dailyCandles == null || dailyCandles.isEmpty()) return 0.0;
        int start = Math.max(0, dailyCandles.size() - lookbackDays);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < dailyCandles.size(); i++) {
            sum += dailyCandles.get(i).volume();
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    public double computeAvgDailyTurnoverCr(List<Candle> dailyCandles, int lookbackDays) {
        if (dailyCandles == null || dailyCandles.isEmpty()) return 0.0;
        int start = Math.max(0, dailyCandles.size() - lookbackDays);
        double sumTurnover = 0.0;
        int count = 0;
        for (int i = start; i < dailyCandles.size(); i++) {
            Candle c = dailyCandles.get(i);
            sumTurnover += c.close() * c.volume();
            count++;
        }
        return count > 0 ? round2((sumTurnover / count) / 10_000_000.0) : 0.0;
    }

    /**
     * Time-normalized RVOL comparing cumulative volume today against 20-day avg volume
     * adjusted by an NSE intraday U-shaped volume participation curve.
     */
    public double computeTimeNormalizedRvol(long todayCumVolume, double avg20dVolume, LocalTime nowIst) {
        if (todayCumVolume <= 0 || avg20dVolume <= 0) {
            return 0.0;
        }
        double expectedFraction = expectedVolumeFractionAt(nowIst);
        double expectedVol = avg20dVolume * expectedFraction;
        if (expectedVol <= 0) return 0.0;
        return round2(todayCumVolume / expectedVol);
    }

    public double expectedVolumeFractionAt(LocalTime timeIst) {
        if (timeIst == null || !timeIst.isAfter(MARKET_OPEN)) {
            return 0.04;
        }
        if (!timeIst.isBefore(MARKET_CLOSE)) {
            return 1.0;
        }
        long totalMins = 375L; // 09:15 to 15:30
        long elapsed = java.time.Duration.between(MARKET_OPEN, timeIst).toMinutes();
        double t = Math.max(0.01, Math.min(1.0, elapsed / (double) totalMins));
        // U-shaped NSE participation curve: front-loaded open + closing surge, flatter midday
        double linear = t;
        double uCurve = 0.22 * Math.sin(Math.PI * t);
        return Math.max(0.04, Math.min(1.0, linear - uCurve + (t < 0.15 ? 0.05 : 0.0)));
    }

    public double[] computeOpeningRange15m(List<Candle> intradayToday) {
        if (intradayToday == null || intradayToday.isEmpty()) {
            return new double[]{0.0, 0.0};
        }
        double orh = Double.MIN_VALUE;
        double orl = Double.MAX_VALUE;
        boolean found = false;
        for (Candle c : intradayToday) {
            LocalTime t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(c.timestampEpoch()), IST).toLocalTime();
            if (!t.isBefore(LocalTime.of(9, 15)) && t.isBefore(LocalTime.of(9, 30))) {
                orh = Math.max(orh, c.high());
                orl = Math.min(orl, c.low());
                found = true;
            }
        }
        if (!found) {
            Candle first = intradayToday.get(0);
            return new double[]{first.high(), first.low()};
        }
        return new double[]{round2(orh), round2(orl)};
    }

    public double computeVwap(List<Candle> intradayToday, double fallbackAtp) {
        if (intradayToday == null || intradayToday.isEmpty()) {
            return round2(fallbackAtp);
        }
        double sumPv = 0.0;
        double sumV = 0.0;
        for (Candle c : intradayToday) {
            double tp = (c.high() + c.low() + c.close()) / 3.0;
            sumPv += tp * c.volume();
            sumV += c.volume();
        }
        if (sumV <= 0) {
            return round2(fallbackAtp);
        }
        return round2(sumPv / sumV);
    }

    public double computeIntradayRangePosition(double ltp, double dayHigh, double dayLow) {
        double span = dayHigh - dayLow;
        if (span <= 0) return 0.5;
        return Math.max(0.0, Math.min(1.0, (ltp - dayLow) / span));
    }

    public double computeRecentBarReturnPct(List<Candle> intraday5m, int barsBack) {
        if (intraday5m == null || intraday5m.size() < 2) return 0.0;
        int idxFrom = Math.max(0, intraday5m.size() - 1 - barsBack);
        double startClose = intraday5m.get(idxFrom).open();
        double endClose = intraday5m.get(intraday5m.size() - 1).close();
        if (startClose <= 0) return 0.0;
        return round2(((endClose - startClose) / startClose) * 100.0);
    }

    public List<Candle> filterSessionDate(List<Candle> intraday, LocalDate sessionDate) {
        if (intraday == null || intraday.isEmpty()) return List.of();
        return intraday.stream()
                .filter(c -> ZonedDateTime.ofInstant(Instant.ofEpochSecond(c.timestampEpoch()), IST)
                        .toLocalDate().equals(sessionDate))
                .toList();
    }

    public double findRecentSwingLow(List<Candle> intradayToday, int lookbackBars, double fallbackLow) {
        if (intradayToday == null || intradayToday.isEmpty()) return fallbackLow;
        int start = Math.max(0, intradayToday.size() - lookbackBars);
        double minLow = Double.MAX_VALUE;
        for (int i = start; i < intradayToday.size(); i++) {
            minLow = Math.min(minLow, intradayToday.get(i).low());
        }
        return minLow == Double.MAX_VALUE ? fallbackLow : round2(minLow);
    }

    public double findRecentSwingHigh(List<Candle> intradayToday, int lookbackBars, double fallbackHigh) {
        if (intradayToday == null || intradayToday.isEmpty()) return fallbackHigh;
        int start = Math.max(0, intradayToday.size() - lookbackBars);
        double maxHigh = Double.MIN_VALUE;
        for (int i = start; i < intradayToday.size(); i++) {
            maxHigh = Math.max(maxHigh, intradayToday.get(i).high());
        }
        return maxHigh == Double.MIN_VALUE ? fallbackHigh : round2(maxHigh);
    }

    public boolean isRisingBreakdownOrBreakoutVolume(List<Candle> intradayToday) {
        if (intradayToday == null || intradayToday.size() < 4) return false;
        Candle last = intradayToday.get(intradayToday.size() - 1);
        double prevAvg = 0.0;
        int start = Math.max(0, intradayToday.size() - 6);
        int count = 0;
        for (int i = start; i < intradayToday.size() - 1; i++) {
            prevAvg += intradayToday.get(i).volume();
            count++;
        }
        return count > 0 && last.volume() >= 1.25 * (prevAvg / count);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

