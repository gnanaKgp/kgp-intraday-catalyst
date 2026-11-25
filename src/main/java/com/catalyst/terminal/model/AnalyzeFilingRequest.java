package com.catalyst.terminal.model;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeFilingRequest(
        String symbol,
        @NotBlank(message = "companyName is required") String companyName,
        String headline,
        String filingText,
        String attachmentUrl,
        Double marketCapCrOverride
) {}

