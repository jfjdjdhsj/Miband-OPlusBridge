// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.notify;

import io.github.jfjdjdhsj.mibandoplusbridge.protocol.BandNotificationCommand;

/**
 * Bands draw the call from the notification packet. Watches (Redmi Watch, Xiaomi Watch, and the
 * same split OPPO's own watches use) draw it from the phone's HFP audio gateway: the link has to
 * be up before the watch will show a call, and an answered call moves the SCO audio onto the watch.
 */
public final class WatchCallRoute {
    public enum Link { NONE, PROFILE, HANDSET }

    private WatchCallRoute() {}

    public static Link link(boolean watch, int callState) {
        if (!watch || callState == 0) return Link.NONE;
        if (callState == BandNotificationCommand.CALL_ACTIVE) return Link.HANDSET;
        return Link.PROFILE;
    }
}
