// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SessionLogTest {
    @Test public void onlyTheNewestThreeModuleStartsRemain() {
        String log = """
                old preamble
                --- module start 1 ---
                one
                --- module start 2 ---
                two
                --- module start 3 ---
                three
                --- module start 4 ---
                four
                """;
        String kept = SessionLog.keepLastStarts(log, SessionLog.KEPT_STARTS);
        assertFalse(kept.contains("module start 1 "));
        assertFalse(kept.contains("\none\n"));
        assertTrue(kept.contains("--- module start 2 ---"));
        assertTrue(kept.contains("two"));
        assertTrue(kept.contains("--- module start 4 ---"));
        assertTrue(kept.contains("four"));
        assertEquals(3, count(kept));
    }

    @Test public void redactStripsAMacBeforeItCanReachTheLog() {
        String clean = SessionLog.redact("read failed AA:bb:cc:dd:ee:ff\nstill");
        assertFalse(clean.toLowerCase().contains("aa:bb"));
        assertFalse(clean.contains("\n"));
        assertEquals("read failed mac still", clean);
        assertEquals("gone mac", SessionLog.redact("gone aa-bb-cc-dd-ee-ff"));
    }

    @Test public void fewerThanThreeStartsAreLeftIntact() {
        String log = "--- module start 1 ---\nonly\n";
        assertEquals(log, SessionLog.keepLastStarts(log, 3));
    }

    private static int count(String text) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(SessionLog.START_MARK, from)) >= 0) {
            count++;
            from += SessionLog.START_MARK.length();
        }
        return count;
    }
}
