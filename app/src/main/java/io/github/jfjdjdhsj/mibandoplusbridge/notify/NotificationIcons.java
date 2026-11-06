// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.notify;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/** Rasterizes an installed app icon to the square size the band requested. */
public final class NotificationIcons {
    private NotificationIcons() {}

    public static boolean available(Context context, String packageName) {
        return icon(context, packageName) != null;
    }

    public static int[] argb(Context context, String packageName, int size) {
        if (size < 1 || size > 256) throw new IllegalArgumentException("ICON_BITMAP");
        Drawable drawable = icon(context, packageName);
        if (drawable == null) throw new IllegalArgumentException("ICON_PACKAGE");
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            int[] pixels = new int[size * size];
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
            return pixels;
        } finally {
            bitmap.recycle();
        }
    }

    private static Drawable icon(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isBlank()) return null;
        try {
            return context.getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }
}
