package io.realworld.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Timestamps are stored as epoch milliseconds and rendered as ISO-8601 UTC strings. */
public final class Times {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    /** Returns a timestamp that is strictly later than the previous one. */
    public static long after(long previous) {
        return Math.max(now(), previous + 1);
    }

    public static String format(long epochMillis) {
        return ISO.format(Instant.ofEpochMilli(epochMillis));
    }
}
