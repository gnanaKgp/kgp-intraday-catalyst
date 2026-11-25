package com.catalyst.terminal.model.funnel;

public record Candle(
        long timestampEpoch,
        double open,
        double high,
        double low,
        double close,
        long volume
) {}

