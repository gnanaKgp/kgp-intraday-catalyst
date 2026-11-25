package com.catalyst.terminal.model.funnel;

import java.time.LocalDateTime;

public record FunnelSetupRecord(
        String symbol,
        String securityId,
        String companyName,
        String sector,
        TradeSide side,
        double compositeScore,
        int catalystScore,
        String stageSource,
        String whyMoving,
        String technicalTrigger,
        double ltp,
        double dayChangePct,
        double vwap,
        double orh,
        double orl,
        double pdh,
        double pdl,
        double rvol,
        double rsVsNifty,
        double rsVsSector,
        ScoreBreakdown scoreBreakdown,
        GuardrailStatus guardrails,
        ExecutionPlan executionPlan,
        LocalDateTime evaluatedAt
) {}

