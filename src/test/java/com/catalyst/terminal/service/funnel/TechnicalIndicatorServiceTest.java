package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.model.funnel.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TechnicalIndicatorServiceTest {

    private final TechnicalIndicatorService service = new TechnicalIndicatorService();

    @Test
    void computesAtr14TurnoverAndTimeNormalizedRvol() {
        List<Candle> daily = new ArrayList<>();
        long baseTs = ZonedDateTime.of(2026, 11, 1, 9, 15, 0, 0, TechnicalIndicatorService.IST).toEpochSecond();
        for (int i = 0; i < 20; i++) {
            // Close=500, High=505, Low=495 (TR=10), Vol=1,000,000 => Turnover = 50 Cr/day
            daily.add(new Candle(baseTs + i * 86400L, 500, 505, 495, 500, 1_000_000L));
        }
        assertEquals(10.0, service.computeAtr14(daily), 0.01);
        assertEquals(50.0, service.computeAvgDailyTurnoverCr(daily, 20), 0.01);

        double rvolMorning = service.computeTimeNormalizedRvol(600_000L, 1_000_000.0, LocalTime.of(9, 45));
        assertTrue(rvolMorning > 3.0, "Expected morning RVOL > 3x, got " + rvolMorning);
    }

    @Test
    void computes15mOpeningRangeAndVwap() {
        LocalDate d = LocalDate.of(2026, 11, 25);
        long t0 = ZonedDateTime.of(d, LocalTime.of(9, 15), TechnicalIndicatorService.IST).toEpochSecond();
        long t1 = ZonedDateTime.of(d, LocalTime.of(9, 25), TechnicalIndicatorService.IST).toEpochSecond();
        long t2 = ZonedDateTime.of(d, LocalTime.of(9, 35), TechnicalIndicatorService.IST).toEpochSecond();

        List<Candle> bars = List.of(
                new Candle(t0, 200, 205, 198, 204, 1000),
                new Candle(t1, 204, 208, 201, 206, 2000),
                new Candle(t2, 206, 215, 205, 214, 3000)
        );
        double[] orRange = service.computeOpeningRange15m(bars);
        assertEquals(208.0, orRange[0], 0.01);
        assertEquals(198.0, orRange[1], 0.01);

        double vwap = service.computeVwap(bars, 200.0);
        assertTrue(vwap > 205.0 && vwap < 212.0);
    }
}

