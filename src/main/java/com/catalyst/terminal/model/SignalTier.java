package com.catalyst.terminal.model;

public enum SignalTier {
    TIER_1("Tier 1 — High-Conviction / Circuit Potential", 90),
    TIER_2("Tier 2 — Strong Intraday Mover", 70),
    TIER_3("Tier 3 — Moderate / Needs Technical Confirmation", 40),
    NOISE("Noise — Discard", 0);

    private final String label;
    private final int minScore;

    SignalTier(String label, int minScore) {
        this.label = label;
        this.minScore = minScore;
    }

    public String getLabel() {
        return label;
    }

    public int getMinScore() {
        return minScore;
    }

    public static SignalTier fromScore(int score) {
        if (score >= 90) return TIER_1;
        if (score >= 70) return TIER_2;
        if (score >= 40) return TIER_3;
        return NOISE;
    }
}

