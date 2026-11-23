package org.acme.employeescheduling.rest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;

/** Shared timestamp and input helpers for the internal dispatch workflow. */
final class WorkflowSupport {
    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private WorkflowSupport() { }

    static String now() {
        return LocalDateTime.now().format(DB_TIME);
    }

    static String text(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('T', ' ');
        if (!normalized.contains(":")) normalized += " 00:00:00";
        if (normalized.length() == 16) normalized += ":00";
        for (DateTimeFormatter formatter : List.of(DB_TIME,
                optionalSeconds("M/d/yy H:mm"), optionalSeconds("M/d/yyyy H:mm"),
                optionalSeconds("yyyy/M/d H:mm"))) {
            try { return LocalDateTime.parse(normalized, formatter); }
            catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("DATETIME_INVALID");
    }

    private static DateTimeFormatter optionalSeconds(String pattern) {
        return new DateTimeFormatterBuilder().appendPattern(pattern)
                .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
                .toFormatter();
    }

    static String dbDateTime(String value) {
        LocalDateTime parsed = parseDateTime(value);
        return parsed == null ? null : parsed.format(DB_TIME);
    }
}
