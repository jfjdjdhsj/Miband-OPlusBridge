// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DozeWhitelistTest {
    private static final String BRIDGE = "io.github.jfjdjdhsj.mibandoplusbridge";

    @Test public void matchesThePackageFieldAfterSystemEntries() {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 40; i++) list.append("system-excidle,com.android.pkg").append(i).append(",1000\n");
        assertTrue(list.length() > 256);
        assertFalse(list.substring(0, 256).contains(BRIDGE));
        list.append("user,").append(BRIDGE).append(",10321\n");
        assertTrue(OwnershipController.whitelistContains(list.toString(), BRIDGE));
    }

    @Test public void rejectsALongerPackageThatContainsTheName() {
        String list = "user," + BRIDGE + ".extra,10321\n";
        assertFalse(OwnershipController.whitelistContains(list, BRIDGE));
    }

    @Test public void readsTheWholeStreamUpToTheLimit() throws Exception {
        byte[] body = "user,com.example,1\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("user,com.example,1\n",
                OwnershipController.readLimited(new ByteArrayInputStream(body), body.length));
    }

    @Test public void refusesATruncatedStream() throws Exception {
        byte[] body = "system-excidle,com.android.settings,1000\n".getBytes(StandardCharsets.UTF_8);
        try {
            OwnershipController.readLimited(new ByteArrayInputStream(body), 8);
            fail("truncated output must not look like a complete whitelist");
        } catch (IOException limited) {
            assertEquals("OUTPUT_LIMIT", limited.getMessage());
        }
    }
}
