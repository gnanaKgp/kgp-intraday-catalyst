package com.catalyst.terminal.model.funnel;

public record ScoreBreakdown(
        double catalyst,
        double rvol,
        double structure,
        double relativeStrength,
        double liquidity,
        double compositeTotal
) {}

