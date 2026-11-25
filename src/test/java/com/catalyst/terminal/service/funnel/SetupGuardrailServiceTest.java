package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.config.FunnelProperties;
import com.catalyst.terminal.model.funnel.TradeSide;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class SetupGuardrailServiceTest {

    private final SetupGuardrailService guardrails = new SetupGuardrailService(new FunnelProperties());

    @Test
    void passesValidSetupAndCalculates1PctRiskQuantity() {
        // Capital 100,000, 1% risk = 1,000. Entry=510, Stop=500 (VWAP ~501), Risk=10 => Qty=100, Target=530
        var eval = guardrails.evaluate(
                true, 65.0, 510.0, 500.0, 12.0,
                501.0, 498.0, 512.0, 508.0, 492.0,
                510.0, 498.0, 0.04, TradeSide.LONG, LocalTime.of(10, 15)
        );
        assertTrue(eval.status().passed(), "Reasons: " + eval.status().disqualificationReasons());
        assertNotNull(eval.executionPlan());
        assertEquals(100, eval.executionPlan().quantity());
        assertTrue(eval.executionPlan().rrRatio() >= 2.0);
    }

    @Test
    void rejectsExhaustedAndIlliquidAndMiddayBreakout() {
        // Moved 20% = 40 pts on ATR14=10 => 4.0x ATR exhausted; outside Nifty 500; midday chop
        var eval = guardrails.evaluate(
                false, 12.0, 222.0, 185.0, 10.0,
                210.0, 205.0, 222.0, 190.0, 180.0,
                222.0, 185.0, 0.10, TradeSide.LONG, LocalTime.of(12, 15)
        );
        assertFalse(eval.status().passed());
        assertFalse(eval.status().liquidityPassed());
        assertFalse(eval.status().exhaustionPassed());
        assertFalse(eval.status().middayChopPassed());
        assertTrue(eval.atrPenalty() > 0);
    }

    @Test
    void rejectsSetupWhenOverheadResistanceCapsRewardBelow2R() {
        // Entry=510, Stop=500 (risk=10), PDH resistance at 522 (reward=12 => 1.2R < 2.0R)
        var eval = guardrails.evaluate(
                true, 60.0, 510.0, 500.0, 15.0,
                501.0, 498.0, 512.0, 522.0, 490.0,
                512.0, 498.0, 0.04, TradeSide.LONG, LocalTime.of(10, 0)
        );
        assertFalse(eval.status().riskRewardPassed());
        assertFalse(eval.status().passed());
    }

    @Test
    void appliesTenPointPenaltyForSoftAtrExtension() {
        // Moved 22 pts on ATR14=10 => 2.20x ATR (between 2.0x soft and 2.5x hard limit)
        var eval = guardrails.evaluate(
                true, 60.0, 522.0, 500.0, 10.0,
                512.0, 508.0, 524.0, 510.0, 492.0,
                524.0, 500.0, 0.04, TradeSide.LONG, LocalTime.of(10, 15)
        );
        assertTrue(eval.status().exhaustionPassed());
        assertTrue(eval.status().passed(), "Reasons: " + eval.status().disqualificationReasons());
        assertEquals(10.0, eval.atrPenalty());
    }
}

