// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class TryHookTest {
    @Test public void aFailedInstallDoesNotStopTheNextOne() {
        int[] ran = {0};
        HookRuntime.tryHook("TEST_HOOK", () -> ran[0]++);
        HookRuntime.tryHook("TEST_HOOK_MISSING", () -> {
            throw new NoSuchMethodException("reconnectDevice");
        });
        HookRuntime.tryHook("TEST_HOOK_NEXT", () -> ran[0]++);
        assertEquals(2, ran[0]);
    }
}
