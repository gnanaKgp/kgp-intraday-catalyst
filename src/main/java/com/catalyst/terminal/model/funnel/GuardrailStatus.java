package com.catalyst.terminal.model.funnel;

import java.util.List;

public record GuardrailStatus(
        boolean passed,
        boolean liquidityPassed,
        boolean exhaustionPassed,
        boolean middayChopPassed,
        boolean riskRewardPassed,
        boolean middayPullbackOnly,
        double atr14,
        double atrMultiple,
        double avgTurnover20dCr,
        double spreadPct,
        List<String> disqualificationReasons
) {}

