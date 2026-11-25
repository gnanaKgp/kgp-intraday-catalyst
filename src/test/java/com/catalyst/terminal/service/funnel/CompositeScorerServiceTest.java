package com.catalyst.terminal.service.funnel;

import com.catalyst.terminal.model.funnel.TradeSide;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompositeScorerServiceTest {

    private final CompositeScorerService scorer = new CompositeScorerService();

    @Test
    void computesExactWeightedCompositeScore() {
        var b = scorer.score(
                90,     // 0.25 * 90 = 22.5
                3.5,    // rvol=100 => 0.25 * 100 = 25.0
                TradeSide.LONG,
                515.0, 505.0, 510.0, 498.0, 512.0, 495.0, 0.90, true, // struct=100 => 25.0
                1.2, 0.2, 0.6, // rs=100 => 15.0
                80.0, 0.04,    // liq=100 => 10.0
                0.0
        );
        assertEquals(97.5, b.compositeTotal(), 0.01);
        assertEquals(100.0, b.rvol(), 0.01);
        assertEquals(100.0, b.structure(), 0.01);
        assertEquals(100.0, b.relativeStrength(), 0.01);
        assertEquals(100.0, b.liquidity(), 0.01);
    }
}

