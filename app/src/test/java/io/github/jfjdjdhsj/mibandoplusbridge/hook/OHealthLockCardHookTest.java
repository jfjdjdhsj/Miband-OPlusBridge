// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OHealthLockCardHookTest {
    @Test public void heartPayloadMatchesTheLockScreenCard() {
        assertEquals("{\"value\":87,\"timeStamp\":1791773400000}",
                OHealthLockCardHook.heartPayload(87, 1_791_773_400_000L));
    }

    @Test public void aNewerBandSampleReplacesAnEmptyOrOlderRow() {
        assertTrue(OHealthLockCardHook.newerHeart("", 10));
        assertTrue(OHealthLockCardHook.newerHeart("{\"value\":60,\"timeStamp\":9}", 10));
        assertFalse(OHealthLockCardHook.newerHeart("{\"value\":60,\"timeStamp\":10}", 10));
        assertFalse(OHealthLockCardHook.newerHeart("{\"value\":60,\"timeStamp\":11}", 10));
        assertTrue(OHealthLockCardHook.newerHeart("not-json", 10));
    }

    @Test public void stepsKeepWhicheverTotalIsAhead() {
        assertEquals(43, OHealthLockCardHook.stepToShow(43, 21));
        assertEquals(8000, OHealthLockCardHook.stepToShow(43, 8000));
        assertEquals(43, OHealthLockCardHook.stepToShow(43, -1));
    }
}
