package com.catalyst.terminal.model.funnel;

public record ExecutionPlan(
        TradeSide side,
        double entry,
        double stopLoss,
        double target,
        int quantity,
        double riskInr,
        double riskPerShare,
        double rewardPerShare,
        double rrRatio
) {}

