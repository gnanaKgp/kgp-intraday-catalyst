package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.config.FunnelProperties;
import com.catalyst.terminal.config.TraderDhanProperties;
import com.catalyst.terminal.model.funnel.Candle;
import com.catalyst.terminal.model.funnel.TradeSide;
import com.catalyst.terminal.service.dhan.DhanAuthService;
import com.catalyst.terminal.service.dhan.DhanInstrumentService;
import com.catalyst.terminal.service.dhan.DhanMarketGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BacktestServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private BacktestService createService() {
        FunnelProperties fp = new FunnelProperties();
        TraderDhanProperties dp = new TraderDhanProperties();
        ObjectMapper om = new ObjectMapper();
        DhanAuthService auth = new DhanAuthService(dp, HttpClient::newHttpClient, om);
        DhanInstrumentService inst = new DhanInstrumentService(dp, HttpClient::newHttpClient);
        DhanMarketGateway gw = new DhanMarketGateway(auth, HttpClient::newHttpClient, om);
        TechnicalIndicatorService ind = new TechnicalIndicatorService();
        SetupGuardrailService guard = new SetupGuardrailService(fp);
        CompositeScorerService scorer = new CompositeScorerService();
        return new BacktestService(fp, inst, gw, null, null, ind, guard, scorer);
    }

    @Test
    void replaysSessionWithoutLookaheadAndDetectsTargetHit() {
        BacktestService svc = createService();
        LocalDate d = LocalDate.of(2026, 11, 25);

        // 20 prior daily candles: Close=500, High=508, Low=492 (ATR14=16), Vol=1,000,000 (Turnover=50 Cr)
        List<Candle> daily = new ArrayList<>();
        long dBase = ZonedDateTime.of(2026, 11, 1, 9, 15, 0, 0, IST).toEpochSecond();
        for (int i = 0; i < 20; i++) {
            daily.add(new Candle(dBase + i * 86400L, 500, 508, 492, 500, 1_000_000L));
        }

        // Intraday session: 09:15-09:30 ORH=506, ORL=498; 09:35 breaks out to 508 with high volume; 10:15 hits 2R target
        List<Candle> bars = List.of(
                bar(d, 9, 15, 500, 504, 498, 502, 120_000),
                bar(d, 9, 20, 502, 506, 500, 504, 130_000),
                bar(d, 9, 25, 504, 505, 501, 503, 120_000),
                bar(d, 9, 30, 503, 505, 502, 504, 150_000),
                bar(d, 9, 35, 504, 509, 504, 509, 300_000),
                bar(d, 9, 45, 509, 515, 508, 514, 180_000),
                bar(d, 10, 0, 514, 526, 513, 524, 220_000),
                bar(d, 15, 15, 524, 525, 520, 522, 90_000)
        );

        var out = svc.replaySymbolSession(
                "RVNL", "NIFTY", true, d, daily, bars,
                Map.of(), Map.of(),
                90, TradeSide.LONG, "LIVE_FEED",
                75.0, 100_000.0, 1.0
        );

        assertNotNull(out.passedTrade(), "Expected passed trade; filtered=" + out.bestFiltered());
        assertEquals("TARGET_HIT", out.passedTrade().outcome());
        assertTrue(out.passedTrade().pnlInr() > 0);
        assertEquals(2.0, out.passedTrade().rMultipleRealized(), 0.05);
        assertEquals("09:35", out.passedTrade().signalTimeIst());
    }

    private static Candle bar(LocalDate d, int h, int m, double o, double hi, double lo, double c, long v) {
        long ts = ZonedDateTime.of(d, LocalTime.of(h, m), IST).toEpochSecond();
        return new Candle(ts, o, hi, lo, c, v);
    }
}

