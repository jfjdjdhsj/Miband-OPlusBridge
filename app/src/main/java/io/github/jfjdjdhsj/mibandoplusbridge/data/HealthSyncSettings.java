// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.data;

import android.content.Context;

/** User-facing schedule for pulling health history off the band. */
public final class HealthSyncSettings {
    public static final long DEFAULT_INTERVAL_MINUTES = 30;
    /** A shorter period asks the band for the same day list faster than it can produce a new one. */
    public static final long MIN_INTERVAL_MINUTES = 5;
    public static final long MAX_INTERVAL_MINUTES = 7 * 24 * 60;
    /** Opening the app twice in a row should not pull the same files again. */
    public static final long OPEN_SYNC_COOLDOWN_MS = 5 * 60_000L;

    private static final String PREFS = "health-sync";
    private static final String ENABLED = "enabled";
    private static final String INTERVAL = "intervalMinutes";
    private static final String ON_OPEN = "syncOnOpen";
    private static final String LAST_OPEN_SYNC = "lastOpenSyncAtMs";

    private HealthSyncSettings() {}

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    public static boolean setEnabled(Context context, boolean on) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).commit();
    }

    public static long intervalMinutes(Context context) {
        long stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(INTERVAL, DEFAULT_INTERVAL_MINUTES);
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, stored));
    }

    public static boolean setIntervalMinutes(Context context, long minutes) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(INTERVAL, Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes)))
                .commit();
    }

    public static boolean syncOnOpen(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON_OPEN, true);
    }

    public static boolean setSyncOnOpen(Context context, boolean on) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON_OPEN, on).commit();
    }

    public static long lastOpenSyncAtMs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_OPEN_SYNC, 0);
    }

    public static void recordOpenSync(Context context, long atMs) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_OPEN_SYNC, atMs).apply();
    }
}
