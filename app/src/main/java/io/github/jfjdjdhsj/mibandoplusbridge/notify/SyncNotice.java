// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import io.github.jfjdjdhsj.mibandoplusbridge.MainActivity;
import io.github.jfjdjdhsj.mibandoplusbridge.data.ChinaTime;

/**
 * One short notice per completed automatic sync. The foreground notice only says the link is up;
 * this one says the data actually moved and when.
 */
public final class SyncNotice {
    private static final String CHANNEL = "sync-result";
    private static final int HEALTH_ID = 0x5359_0001;
    private static final int WEATHER_ID = 0x5359_0002;

    private SyncNotice() {}

    public static void health(Context context, long atMs) {
        post(context, HEALTH_ID, "健康数据已同步", "同步时间 " + ChinaTime.clock(atMs), atMs);
    }

    public static void weather(Context context, long atMs) {
        post(context, WEATHER_ID, "天气已同步", "同步时间 " + ChinaTime.clock(atMs), atMs);
    }

    private static void post(Context context, int id, String title, String text, long atMs) {
        if (context == null) return;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL, "同步提醒", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setDescription("自动同步健康数据和天气后提醒，并显示同步时间。");
            manager.createNotificationChannel(channel);
            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Notification notice = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setWhen(atMs)
                    .setShowWhen(true)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(context, id, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build();
            manager.notify(id, notice);
        } catch (RuntimeException ignored) {
            // A missing notification permission must not fail the sync that just succeeded.
        }
    }
}
