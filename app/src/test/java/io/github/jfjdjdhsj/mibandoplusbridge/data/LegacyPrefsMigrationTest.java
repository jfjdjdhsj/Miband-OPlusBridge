// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class LegacyPrefsMigrationTest {
    @Test public void keepsOnlyPrefsFilesUnderDataMisc() {
        String listing = "/data/misc/apexdata/09d9/prefs/io.github.jfjdjdhsj.mibandoplusbridge/notification-settings.xml\n"
                + "Permission denied\n"
                + "/data/misc/x/prefs/io.github.jfjdjdhsj.mibandoplusbridge/../evil.xml\n"
                + "/data/data/other/shared_prefs/a.xml\n";
        assertEquals(List.of(
                "/data/misc/apexdata/09d9/prefs/io.github.jfjdjdhsj.mibandoplusbridge/notification-settings.xml"),
                LegacyPrefsMigration.lines(listing));
    }
}
