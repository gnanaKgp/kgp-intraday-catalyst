package com.catalyst.terminal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Exact 10-field institutional evaluation schema returned by the LLM evaluator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiCatalystResult(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("direction") Direction direction,
        @JsonProperty("catalyst_score") int catalystScore,
        @JsonProperty("category") FilingCategory category,
        @JsonProperty("order_value_cr") Double orderValueCr,
        @JsonProperty("order_to_mcap_pct") Double orderToMcapPct,
        @JsonProperty("is_actionable_intraday") boolean isActionableIntraday,
        @JsonProperty("headline_summary") String headlineSummary,
        @JsonProperty("institutional_rationale") String institutionalRationale,
        @JsonProperty("key_risk") String keyRisk
) {}

