package com.catalyst.terminal.model;

import java.time.Instant;

public record MarketSnapshot(
        String symbol,
        Double ltp,
        Double dayChangePct,
        Double marketCapCr,
        String source,
        Instant fetchedAt
) {
    public static MarketSnapshot empty(String symbol) {
        return new MarketSnapshot(symbol, null, null, null, "UNAVAILABLE", Instant.now());
    }
}

