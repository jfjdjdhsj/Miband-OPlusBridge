// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The bridge always shows and logs China time. The phone may be set to another zone, and the
 * band clock and every status line must still agree with the user's wall clock.
 */
public final class ChinaTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZONE);
    private static final DateTimeFormatter SHORT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZONE);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZONE);
    private static final DateTimeFormatter LOG =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZONE);

    private ChinaTime() {}

    public static String dateTime(long epochMs) { return DATE_TIME.format(Instant.ofEpochMilli(epochMs)); }

    public static String dateTime(Instant instant) { return DATE_TIME.format(instant); }

    public static String shortTime(long epochMs) { return SHORT.format(Instant.ofEpochMilli(epochMs)); }

    public static String clock(long epochMs) { return CLOCK.format(Instant.ofEpochMilli(epochMs)); }

    /** Session log prefix. Milliseconds stay so two events in the same second keep their order. */
    public static String logStamp(Instant instant) { return LOG.format(instant); }
}
