package com.catalyst.terminal.model.funnel;

import java.time.Instant;

public record DhanQuote(
        String securityId,
        String exchangeSegment,
        double ltp,
        double atp,
        long volume,
        double open,
        double high,
        double low,
        double prevClose,
        long totalBuyQty,
        long totalSellQty,
        double bestBid,
        double bestAsk,
        double spreadPct,
        Instant fetchedAt
) {
    public double dayChangePct() {
        return prevClose > 0 ? Math.round(((ltp - prevClose) / prevClose) * 10000.0) / 100.0 : 0.0;
    }
}

