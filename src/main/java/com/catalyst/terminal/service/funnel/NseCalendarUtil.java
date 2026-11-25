package com.catalyst.terminal.service.funnel;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Basic NSE trading day helper checking weekends and fixed/known NSE market holidays.
 */
public final class NseCalendarUtil {

    // Major fixed/annual NSE holidays (extendable via config if needed)
    private static final Set<LocalDate> KNOWN_HOLIDAYS = Set.of(
            LocalDate.of(2026, 1, 26),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 8, 15),
            LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 11, 24),
            LocalDate.of(2026, 12, 25)
    );

    private NseCalendarUtil() {}

    public static boolean isTradingDay(LocalDate date) {
        if (date == null) return false;
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !KNOWN_HOLIDAYS.contains(date);
    }
}

