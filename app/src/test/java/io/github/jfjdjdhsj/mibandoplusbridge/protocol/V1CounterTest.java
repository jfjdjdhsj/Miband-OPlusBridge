// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.protocol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class V1CounterTest {
    @Test public void lastTwoValuesPassAndTheNextClosesTheSession() throws Exception {
        SppDiagnosticClient client = new SppDiagnosticClient(new android.content.ContextWrapper(null) {
            @Override public android.content.Context getApplicationContext() { return this; }
        }, code -> { });
        client.armV1Counter(0xfffe);
        assertEquals(0xfffe, client.takeV1Counter());
        assertEquals(0xffff, client.takeV1Counter());
        try {
            client.takeV1Counter();
            fail("counter past 0xffff reuses the CCM nonce");
        } catch (SppDiagnosticClient.Failure exhausted) {
            assertEquals("V1_COUNTER_EXHAUSTED", exhausted.code);
        }
    }
}
