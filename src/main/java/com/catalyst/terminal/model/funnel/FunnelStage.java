package com.catalyst.terminal.model.funnel;

public enum FunnelStage {
    OVERNIGHT_CATALYST("8:00–9:00 AM", "News & Catalyst Engine"),
    PRE_MARKET_FILTER("9:07–9:14 AM", "Pre-Market Gap & Volume Filter"),
    OPENING_RANGE_LOCK("9:15–9:30 AM", "15m Observation & Range Lock"),
    LIVE_RANKER("9:30 AM–2:30 PM", "Live Probability Ranker"),
    AUTO_CUTOFF("3:15 PM+", "Auto-Cutoff & Square-Off Window"),
    AFTER_HOURS("After Hours", "Post-Market / Manual Scan");

    private final String windowLabel;
    private final String description;

    FunnelStage(String windowLabel, String description) {
        this.windowLabel = windowLabel;
        this.description = description;
    }

    public String getWindowLabel() { return windowLabel; }
    public String getDescription() { return description; }
}

