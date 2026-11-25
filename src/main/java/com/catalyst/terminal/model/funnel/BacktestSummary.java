package com.catalyst.terminal.model.funnel;

import java.time.LocalDate;
import java.util.List;

public record BacktestSummary(
        LocalDate fromDate,
        LocalDate toDate,
        int tradingDaysScanned,
        int symbolsEvaluated,
        int totalPassedSignals,
        int totalFilteredSignals,
        int wins,
        int losses,
        int targetHits,
        int slHits,
        int timeExits,
        double winRatePct,
        double netPnlInr,
        double returnOnCapitalPct,
        double profitFactor,
        double avgRMultiple,
        double maxDrawdownInr,
        double filteredWouldHaveLostPct,
        List<BacktestTradeResult> passedTrades,
        List<BacktestTradeResult> filteredSetups
) {}

