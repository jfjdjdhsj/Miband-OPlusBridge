// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OHealthOpenHealthTest {
    @Test public void anUnlistedListenerKeepsHealthsOwnEnable() {
        assertFalse(OHealthNotificationAccessHook.replaceRun(false));
        assertTrue(OHealthNotificationAccessHook.replaceRun(true));
    }

    @Test public void stopStaysBlockedOnceTheBandOrTheListenerIsHeld() {
        assertFalse(OHealthNotificationAccessHook.blockStop(false, false));
        assertTrue(OHealthNotificationAccessHook.blockStop(false, true));
        assertTrue(OHealthNotificationAccessHook.blockStop(true, false));
    }

    @Test public void anEmptyCloudListDoesNotCleanARegisteredBand() {
        assertFalse(OHealthDeviceHook.skipEmptyCloudCleanup(false));
        assertTrue(OHealthDeviceHook.skipEmptyCloudCleanup(true));
    }
}
