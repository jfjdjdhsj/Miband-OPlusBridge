// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves SharedPreferences left by the old module out of the LSPosed preference directory.
 *
 * <p>Through 1.6.8 the module declared {@code xposedminversion=93}, so LSPosed pointed
 * {@code getPreferencesDir()} at {@code /data/misc/&lt;id&gt;/prefs/&lt;package&gt;}.
 * A libxposed API 102 module is not redirected, so the same names open an empty
 * {@code shared_prefs} in the data directory. Each old file is copied once, before
 * anything opens a preference. A file already in {@code shared_prefs} stays as it is.</p>
 */
public final class LegacyPrefsMigration {
    private static final String TAG = "OplusBandBridge";
    private static final String MARKER = "legacy-prefs-migrated";
    private static final int ROOT_TIMEOUT_SECONDS = 4;

    private LegacyPrefsMigration() {}

    public static void run(Context context) {
        File marker = new File(context.getNoBackupFilesDir(), MARKER);
        if (marker.exists()) return;
        String pkg = context.getPackageName();
        if (!pkg.matches("[A-Za-z0-9._]+")) return;
        File target = new File(context.getDataDir(), "shared_prefs");
        try {
            String listing = root("for f in /data/misc/*/prefs/" + pkg + "/*.xml /data/misc/*/*/prefs/" + pkg
                    + "/*.xml; do [ -f \"$f\" ] && echo \"$f\"; done; true");
            int copied = 0;
            for (String path : lines(listing)) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.matches("[A-Za-z0-9._-]+\\.xml")) continue;
                File destination = new File(target, name);
                if (destination.exists()) continue;
                byte[] content = rootBytes("cat '" + path.replace("'", "'\\''") + "'");
                if (content.length == 0) continue;
                write(target, destination, content);
                copied++;
            }
            if (!marker.createNewFile() && !marker.exists()) throw new IOException("MARKER_NOT_WRITTEN");
            Log.i(TAG, "LEGACY_PREFS_MIGRATED files=" + copied);
        } catch (IOException | RuntimeException failure) {
            // No root yet, or root timed out. The next process start tries again.
            Log.i(TAG, "LEGACY_PREFS_MIGRATION_DEFERRED " + failure.getMessage());
        }
    }

    static List<String> lines(String listing) {
        List<String> result = new ArrayList<>();
        for (String line : listing.split("\n")) {
            String path = line.trim();
            if (path.startsWith("/data/misc/") && path.endsWith(".xml") && !path.contains("..")) result.add(path);
        }
        return result;
    }

    private static void write(File directory, File destination, byte[] content) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("PREFS_DIR_UNAVAILABLE");
        File temporary = new File(directory, destination.getName() + ".migrating");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(content);
            out.getFD().sync();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("PREFS_RENAME_FAILED");
        }
    }

    private static String root(String command) throws IOException {
        return new String(rootBytes(command), StandardCharsets.UTF_8);
    }

    private static byte[] rootBytes(String command) throws IOException {
        Process process = new ProcessBuilder("su", "-c", command).start();
        try {
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copy(process.getInputStream(), output), "OplusBandPrefsMigration");
            reader.start();
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException("ROOT_TIMEOUT");
            reader.join(1_000);
            if (process.exitValue() != 0) throw new IOException("ROOT_DENIED");
            synchronized (output) {
                return output.toByteArray();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("ROOT_INTERRUPTED");
        } finally {
            process.destroy();
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) > 0) {
                synchronized (output) {
                    output.write(buffer, 0, read);
                }
            }
        } catch (IOException ignored) {
            // The process ended or timed out.
        }
    }
}
