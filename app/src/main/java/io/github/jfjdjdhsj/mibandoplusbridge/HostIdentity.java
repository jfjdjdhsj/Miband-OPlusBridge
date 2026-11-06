// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

/** The host is identified by package name. A system update rotates the signing cert, so a pinned cert would drop the hooks. */
public final class HostIdentity {
    public static final String MI_PACKAGE = "com.mi.health";
    public static final String HEALTH_PACKAGE = "com.heytap.health";
    public static final String DEVICES_PACKAGE = "com.heytap.mydevices";

    private HostIdentity() {}

    public static boolean isSelf() {
        return Binder.getCallingUid() == Process.myUid();
    }

    public static void requireMiCaller(Context context) {
        requireCaller(context, MI_PACKAGE);
    }

    public static void requireCaller(Context context, String packageName) {
        if (!uidHas(context, Binder.getCallingUid(), packageName)) {
            throw new SecurityException("CALLER_NOT_AUTHORIZED");
        }
    }

    /** This UID owns {@code packageName}. A shared UID can list more than one package. */
    public static boolean uidHas(Context context, int uid, String packageName) {
        if (context == null || packageName == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) if (packageName.equals(name)) return true;
        return false;
    }

    public static boolean installed(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }
}
