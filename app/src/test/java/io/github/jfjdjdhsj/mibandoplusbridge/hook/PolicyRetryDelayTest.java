// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PolicyRetryDelayTest {
    @Test public void backsOffAndCapsAtOneMinute() {
        assertEquals(5_000L, OHealthDeviceHook.policyRetryDelayMs(1));
        assertEquals(10_000L, OHealthDeviceHook.policyRetryDelayMs(2));
        assertEquals(20_000L, OHealthDeviceHook.policyRetryDelayMs(3));
        assertEquals(40_000L, OHealthDeviceHook.policyRetryDelayMs(4));
        assertEquals(60_000L, OHealthDeviceHook.policyRetryDelayMs(5));
        assertEquals(60_000L, OHealthDeviceHook.policyRetryDelayMs(6));
    }
}
