// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.notify;

import org.junit.Test;
import static io.github.jfjdjdhsj.mibandoplusbridge.protocol.BandNotificationCommand.CALL_ACTIVE;
import static io.github.jfjdjdhsj.mibandoplusbridge.protocol.BandNotificationCommand.CALL_INCOMING;
import static io.github.jfjdjdhsj.mibandoplusbridge.protocol.BandNotificationCommand.CALL_OUTGOING;
import static org.junit.Assert.assertEquals;

public final class WatchCallRouteTest {
    @Test public void bandsKeepTheNotificationCallScreen() {
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(false, CALL_INCOMING));
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(false, CALL_ACTIVE));
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(false, 0));
    }

    @Test public void watchesOpenHfpForARingAndMoveAudioWhenAnswered() {
        assertEquals(WatchCallRoute.Link.PROFILE, WatchCallRoute.link(true, CALL_INCOMING));
        assertEquals(WatchCallRoute.Link.PROFILE, WatchCallRoute.link(true, CALL_OUTGOING));
        assertEquals(WatchCallRoute.Link.HANDSET, WatchCallRoute.link(true, CALL_ACTIVE));
        assertEquals(WatchCallRoute.Link.NONE, WatchCallRoute.link(true, 0));
    }
}
