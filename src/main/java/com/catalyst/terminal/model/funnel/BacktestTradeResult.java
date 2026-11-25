package com.catalyst.terminal.model.funnel;

import java.time.LocalDate;
import java.util.List;

public record BacktestTradeResult(
        LocalDate sessionDate,
        String symbol,
        String sector,
        TradeSide side,
        String signalTimeIst,
        double compositeScore,
        int catalystScore,
        String catalystSource,
        double rvol,
        double vwap,
        double orh,
        double orl,
        double atr14,
        double atrMultiple,
        double entryPrice,
        double stopLoss,
        double targetPrice,
        int quantity,
        double rrPlanned,
        String exitTimeIst,
        double exitPrice,
        String outcome,
        double pnlInr,
        double rMultipleRealized,
        double mfePct,
        double maePct,
        double eodClosePrice,
        boolean guardrailsPassed,
        List<String> guardrailReasons
) {}

