package com.catalyst.terminal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record CatalystFilingRecord(
        Long id,
        String filingUid,
        String symbol,
        String companyName,
        String rawHeadline,
        String attachmentUrl,
        Direction direction,
        @JsonProperty("catalyst_score") int catalystScore,
        SignalTier tier,
        FilingCategory category,
        @JsonProperty("order_value_cr") Double orderValueCr,
        @JsonProperty("order_to_mcap_pct") Double orderToMcapPct,
        @JsonProperty("is_actionable_intraday") boolean isActionableIntraday,
        @JsonProperty("headline_summary") String headlineSummary,
        @JsonProperty("institutional_rationale") String institutionalRationale,
        @JsonProperty("key_risk") String keyRisk,
        @JsonProperty("market_cap_cr") Double marketCapCr,
        Double ltp,
        @JsonProperty("day_change_pct") Double dayChangePct,
        boolean watchlistHit,
        String sourceMode,
        LocalDateTime evaluatedAt
) {}

