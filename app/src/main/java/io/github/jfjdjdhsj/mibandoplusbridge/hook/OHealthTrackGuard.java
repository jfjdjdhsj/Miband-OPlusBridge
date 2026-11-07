// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook;

import android.content.Context;
import io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed.HookRuntime;
import io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed.XC_MethodHook;
import io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed.XposedBridge;
import io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Keeps the OPPO tracking SDK from initializing before the Application exists.
 *
 * <p>{@code TrackApiImpl} starts {@code checkInit} on its own {@code ES#Track} thread from the
 * constructor. {@code checkInit} hands {@code GlobalApplicationHolder.getAppContext()} to
 * {@code TrackApi.staticInit}, which ends in {@code CapacityGovernor.init}; that calls
 * {@code context.getApplicationContext()} and dereferences the result. Until
 * {@code ActivityThread} has finished {@code Application.attach}, that call returns null, so a
 * check that lands in that window kills the process with a NullPointerException. Installing this
 * module's hooks inside {@code attach} widens the window by several hundred milliseconds, which is
 * why the app started crashing on open.</p>
 *
 * <p>When the process is not ready yet, the call is dropped and replayed on the host's own executor
 * once the application context resolves. Tracking then initializes normally.</p>
 */
public final class OHealthTrackGuard {
    private static final String IMPL = "com.heytap.health.track.TrackApiImpl";
    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_MS = 250;

    private static volatile Context appContext;

    private OHealthTrackGuard() {}

    public static void install(ClassLoader loader) {
        Class<?> impl;
        try {
            impl = XposedHelpers.findClass(IMPL, loader);
        } catch (Throwable absent) {
            HookRuntime.log("OHEALTH_TRACK_GUARD_ABSENT " + absent.getClass().getSimpleName());
            return;
        }
        XposedBridge.hookAllMethods(impl, "checkInit", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (ready()) return;
                param.setResult(null);
                defer(param.thisObject, 1);
            }
        });
        HookRuntime.log("OHEALTH_TRACK_GUARD_INSTALLED");
    }

    /** The context the module captured in {@code Application.attach}. */
    public static void observe(Context context) {
        if (context != null) appContext = context;
    }

    private static boolean ready() {
        Context context = appContext;
        if (context == null) return false;
        try {
            return context.getApplicationContext() != null;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void defer(Object impl, int attempt) {
        if (attempt > MAX_ATTEMPTS) {
            HookRuntime.log("OHEALTH_TRACK_DEFER_GIVE_UP");
            return;
        }
        HookRuntime.main().postDelayed(() -> {
            if (!ready()) {
                defer(impl, attempt + 1);
                return;
            }
            replay(impl);
        }, RETRY_MS);
    }

    /** The host runs the first check on its own executor. Replay there so the main thread stays free. */
    private static void replay(Object impl) {
        try {
            Field field = impl.getClass().getField("executor");
            Object value = field.get(impl);
            if (value instanceof Executor executor) {
                executor.execute(() -> invoke(impl));
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to the main thread.
        }
        HookRuntime.main().post(() -> invoke(impl));
    }

    private static void invoke(Object impl) {
        try {
            Method checkInit = impl.getClass().getMethod("checkInit");
            checkInit.invoke(impl);
        } catch (Throwable failure) {
            HookRuntime.log("OHEALTH_TRACK_REPLAY_FAILED " + failure.getClass().getSimpleName());
        }
    }
}
