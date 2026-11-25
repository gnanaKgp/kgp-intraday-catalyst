package com.catalyst.terminal.model;

import java.time.LocalDateTime;

public record WatchlistItem(
        String symbol,
        String notes,
        LocalDateTime addedAt
) {}

