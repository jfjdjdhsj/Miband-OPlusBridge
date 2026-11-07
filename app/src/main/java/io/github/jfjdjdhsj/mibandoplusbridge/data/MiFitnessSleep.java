// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sleep sessions Mi Fitness already stored locally. A band that never uploads a sleep history
 * file still shows a full night in Mi Fitness, because the vendor app keeps the summary and its
 * stage list in {@code fitness_data.sleep_segment}. Reading that copy lets the OPPO import run
 * without waiting for the band's own file.
 */
public final class MiFitnessSleep {
    private static final int MAX_SESSIONS = 400;
    private static final long MAX_SESSION_MS = 24L * 3_600_000L;
    private static final String SCRIPT = "copy-sleep.sh";

    /** Stage numbers match a band sleep file: 2 deep, 3 light, 4 REM, 5 awake. */
    public record Stage(long startMs, long endMs, int state) {}

    public record Session(long startMs, long endMs, String timezone, boolean complete,
            List<Stage> stages) {}

    private MiFitnessSleep() {}

    public static List<Session> read(Context context, long sinceMs) throws MiFitnessDatabase.Failure {
        File dir = new File(context.getNoBackupFilesDir(), "mihealth-sleep");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new MiFitnessDatabase.Failure("MI_SLEEP_UNREADABLE");
        try {
            copy(dir);
            return open(dir, sinceMs);
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
        }
    }

    private static void copy(File dir) throws MiFitnessDatabase.Failure {
        File script = new File(dir, SCRIPT);
        String dest = dir.getAbsolutePath();
        if (!dest.matches("[A-Za-z0-9_./-]+")) throw new MiFitnessDatabase.Failure("MI_SLEEP_UNREADABLE");
        String source = MiFitnessDatabase.SOURCE;
        String body = "set -eu\n"
                + "user=$(ls -1 " + source + " | grep -E '^[0-9]{5,}$' | head -n 1)\n"
                + "test -n \"$user\"\n"
                + "src=" + source + "/$user/cn/fitness_data\n"
                + "test -f \"$src\"\n"
                + "rm -f " + dest + "/fitness_data " + dest + "/fitness_data-wal\n"
                + "cp -a \"$src\" " + dest + "/fitness_data\n"
                + "if [ -f \"$src-wal\" ]; then cp -a \"$src-wal\" " + dest + "/fitness_data-wal; fi\n"
                + "owner=$(stat -c %u " + dest + ")\n"
                + "group=$(stat -c %g " + dest + ")\n"
                + "chown \"$owner:$group\" " + dest + "/*\n"
                + "chmod 600 " + dest + "/*\n";
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new MiFitnessDatabase.Failure("MI_SLEEP_UNREADABLE");
        }
        MiFitnessDatabase.execute(script.getAbsolutePath());
    }

    private static List<Session> open(File dir, long sinceMs) throws MiFitnessDatabase.Failure {
        File file = new File(dir, "fitness_data");
        if (!file.isFile()) throw new MiFitnessDatabase.Failure("MI_SLEEP_MISSING");
        SQLiteDatabase database = null;
        try {
            // Read-write applies the copied WAL without opening the live shm lock.
            database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            database.rawQuery("PRAGMA busy_timeout=2000", null).close();
            List<Session> sessions = new ArrayList<>();
            try (Cursor rows = database.rawQuery(
                    "SELECT value, zoneName FROM sleep_segment WHERE key='sleep' AND isDeleted=0 AND time>=? "
                            + "ORDER BY time DESC LIMIT " + MAX_SESSIONS,
                    new String[]{Long.toString(Math.max(0, sinceMs) / 1000)})) {
                while (rows.moveToNext()) {
                    Session session = parse(rows.getString(0), rows.isNull(1) ? "" : rows.getString(1));
                    if (session != null) sessions.add(session);
                }
            }
            return sessions;
        } catch (RuntimeException unreadable) {
            throw new MiFitnessDatabase.Failure("MI_SLEEP_UNREADABLE");
        } finally {
            if (database != null) database.close();
        }
    }

    static Session parse(String raw, String zoneName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JSONObject value = new JSONObject(raw);
            long start = value.optLong("bedtime", 0) * 1000L;
            long end = value.optLong("wake_up_time", 0) * 1000L;
            if (start <= 0 || end <= start || end - start > MAX_SESSION_MS) return null;
            String zone = zone(value, zoneName);
            List<Stage> stages = new ArrayList<>();
            JSONArray items = value.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    long stageStart = item.optLong("start_time", 0) * 1000L;
                    long stageEnd = item.optLong("end_time", 0) * 1000L;
                    int state = item.optInt("state", 0);
                    if (state < 2 || state > 5 || stageEnd <= stageStart) continue;
                    stages.add(new Stage(Math.max(stageStart, start), Math.min(stageEnd, end), state));
                }
            }
            stages.sort(java.util.Comparator.comparingLong(Stage::startMs));
            return new Session(start, end, zone, !value.optBoolean("is_uncomplete", false), List.copyOf(stages));
        } catch (org.json.JSONException malformed) {
            return null;
        }
    }

    /** The zone name is authoritative; the quarter-hour offset is the fallback. */
    private static String zone(JSONObject value, String zoneName) {
        String name = zoneName == null ? "" : zoneName.trim();
        if (!name.isEmpty()) {
            try {
                ZoneId.of(name);
                return name;
            } catch (RuntimeException unsupported) { }
        }
        int quarters = value.optInt("timezone", Integer.MIN_VALUE);
        if (quarters == Integer.MIN_VALUE) return ZoneId.systemDefault().getId();
        int total = quarters * 15;
        if (Math.abs(total) > 14 * 60) return ZoneId.systemDefault().getId();
        String sign = total < 0 ? "-" : "+";
        int abs = Math.abs(total);
        return String.format(java.util.Locale.ROOT, "GMT%s%02d:%02d", sign, abs / 60, abs % 60);
    }
}
