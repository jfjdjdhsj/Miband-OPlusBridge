// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import org.junit.Test;

public final class ChinaTimeTest {
    @Test public void formatsTheEpochInChinaTime() {
        assertEquals("1970-01-01 08:00:00", ChinaTime.dateTime(0L));
        assertEquals("08:00", ChinaTime.clock(0L));
        assertEquals("01-01 08:00", ChinaTime.shortTime(0L));
        assertEquals("1970-01-01 08:00:00.000", ChinaTime.logStamp(Instant.ofEpochMilli(0)));
    }

    @Test public void keepsMillisecondsInTheLogStamp() {
        assertEquals("2026-11-07 14:13:29.123", ChinaTime.logStamp(Instant.parse("2026-11-07T06:13:29.123Z")));
    }
}
