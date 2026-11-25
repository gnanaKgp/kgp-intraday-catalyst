package com.catalyst.terminal.model.funnel;

import java.time.LocalDate;
import java.util.List;

public record BacktestRequest(
        LocalDate fromDate,
        LocalDate toDate,
        List<String> symbols,
        Double capitalInr,
        Double riskPct,
        Double minCompositeScore,
        Integer assumedCatalystScore
) {}

