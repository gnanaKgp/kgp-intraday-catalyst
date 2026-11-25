package com.catalyst.terminal.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record PipelineEvent(
        long id,
        String timestamp,
        String stage,
        String level,
        String symbol,
        String message
) {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static PipelineEvent of(long id, String stage, String level, String symbol, String message) {
        return new PipelineEvent(id, LocalTime.now().format(FMT), stage, level, symbol, message);
    }
}

