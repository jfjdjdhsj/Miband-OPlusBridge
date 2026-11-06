// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalPrefsFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void readsAFileLargerThanTheOld32KibCap() throws Exception {
        JSONObject json = new JSONObject();
        json.put("mode", "NATIVE");
        json.put("pad", "x".repeat(40_000));
        java.io.File file = folder.newFile("ownership.json");
        assertTrue(LocalPrefs.writeJson(file, json));
        assertTrue(file.length() > 32_768);
        LocalPrefs.Stored stored = LocalPrefs.readStored(file);
        assertTrue(stored.present);
        assertEquals("NATIVE", stored.json.getString("mode"));
        assertEquals(40_000, stored.json.getString("pad").length());
    }

    @Test public void keepsABrokenFileFromLookingMissing() throws Exception {
        java.io.File file = folder.newFile("ownership.json");
        Files.write(file.toPath(), "{".getBytes(StandardCharsets.UTF_8));
        LocalPrefs.Stored stored = LocalPrefs.readStored(file);
        assertTrue(stored.present);
        assertEquals(0, stored.json.length());
        assertEquals("{", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void treatsAMissingFileAsAbsent() {
        LocalPrefs.Stored stored = LocalPrefs.readStored(new java.io.File(folder.getRoot(), "absent.json"));
        assertFalse(stored.present);
    }
}
