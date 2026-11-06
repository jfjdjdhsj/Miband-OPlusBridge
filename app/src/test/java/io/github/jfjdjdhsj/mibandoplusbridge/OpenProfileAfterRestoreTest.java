// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OpenProfileAfterRestoreTest {
    @Test public void followsTheRestoreResultRatherThanTheStatusText() {
        assertTrue(MainActivity.openProfileAfterRestore(MainActivity.Change.RESTORE, true));
        assertFalse(MainActivity.openProfileAfterRestore(MainActivity.Change.RESTORE, false));
        assertFalse(MainActivity.openProfileAfterRestore(MainActivity.Change.REMOVE, true));
    }
}
