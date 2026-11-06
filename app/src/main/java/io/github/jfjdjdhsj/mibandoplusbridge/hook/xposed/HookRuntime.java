// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.hook.xposed;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * State of one module generation inside one host process.
 *
 * <p>Hot reload loads a new copy of this class. Before the old copy is retired, {@link #close()}
 * stops what it started in the host: worker threads, executors, receivers, content observers, and
 * callbacks queued on the main thread. Hooks are not closed here. The framework hands their handles
 * to the new generation, which unhooks them.</p>
 */
public final class HookRuntime {
    public static final String TAG = "OplusBandBridge";

    private static volatile XposedInterface framework;
    private static final Deque<Runnable> cleanups = new ArrayDeque<>();
    private static final Map<String, Object> carried = new HashMap<>();
    private static Handler main;
    /** Handed to old code that is still running after a hot reload. It drops every post. */
    private static Handler retired;
    private static boolean closed;

    private HookRuntime() {}

    public static void attach(XposedInterface value) { framework = value; }

    static XposedInterface framework() {
        XposedInterface current = framework;
        if (current == null) throw new IllegalStateException("XPOSED_FRAMEWORK_NOT_ATTACHED");
        return current;
    }

    public static void log(String line) {
        write(Log.INFO, line, null);
    }

    public static void log(String line, Throwable failure) {
        write(Log.WARN, line, failure);
    }

    /** One hook install. A missing host method is logged under the token, and the rest of the installs continue. */
    public static void tryHook(String token, ThrowingRunnable install) {
        try {
            install.run();
        } catch (Throwable failure) {
            log(token, failure);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /** A log failure stays inside the logger. The hook that called it keeps running. */
    private static void write(int priority, String line, Throwable failure) {
        try {
            Log.println(priority, TAG, failure == null ? line : line + '\n' + Log.getStackTraceString(failure));
        } catch (RuntimeException ignored) {
            // Unit tests run without android.util.Log.
        }
        XposedInterface current = framework;
        if (current == null) return;
        try {
            if (failure == null) current.log(priority, TAG, line);
            else current.log(priority, TAG, line, failure);
        } catch (RuntimeException ignored) {
            // The framework log is best effort.
        }
    }

    /** Main-thread handler of this generation. Its queued callbacks are dropped on reload. */
    public static synchronized Handler main() {
        if (closed) {
            if (retired == null) {
                retired = new Handler(Looper.getMainLooper()) {
                    @Override public boolean sendMessageAtTime(Message message, long uptimeMillis) { return false; }
                };
            }
            return retired;
        }
        if (main == null) {
            Handler created = new Handler(Looper.getMainLooper());
            main = created;
            track(() -> created.removeCallbacksAndMessages(null));
        }
        return main;
    }

    public static HandlerThread thread(String name) {
        HandlerThread thread = new HandlerThread(name);
        thread.start();
        track(thread::quit);
        return thread;
    }

    /**
     * One daemon worker thread. A full bounded queue still rejects with an exception, as before.
     * After a hot reload the executor stops taking work: running and queued tasks finish, and a
     * task submitted later is dropped. Throwing there would crash the host when old code that is
     * still running, often on this same worker, queues its next step.
     */
    public static ThreadPoolExecutor worker(String name, int capacity) {
        BlockingQueue<Runnable> queue = capacity > 0 ? new ArrayBlockingQueue<>(capacity) : new LinkedBlockingQueue<>();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue, runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }, (task, owner) -> {
            if (owner.isShutdown()) return;
            throw new RejectedExecutionException("Task " + task + " rejected from " + name);
        });
        track(executor::shutdown);
        return executor;
    }

    public static void observe(ContentResolver resolver, Uri uri, boolean descendants, ContentObserver observer) {
        resolver.registerContentObserver(uri, descendants, observer);
        track(() -> resolver.unregisterContentObserver(observer));
    }

    /**
     * Registers on the next main-thread turn. A receiver registered inside {@code Application.attach}
     * is filed under the bare ContextImpl, and the platform then switches that context's outer
     * context to the Application. {@code unregisterReceiver} looks under the Application and fails
     * with "Receiver not registered", so a hot reload could not remove it.
     */
    public static void receive(Context context, BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler scheduler, int flags) {
        main().post(() -> {
            synchronized (HookRuntime.class) {
                if (closed) return;
            }
            try {
                context.registerReceiver(receiver, filter, permission, scheduler, flags);
                track(() -> context.unregisterReceiver(receiver));
            } catch (RuntimeException failure) {
                log("RECEIVER_REGISTER_FAILED " + receiver.getClass().getName(), failure);
            }
        });
    }

    /** Registers a cleanup. After a hot reload it runs at once, because no later close will. */
    public static void track(Runnable cleanup) {
        synchronized (HookRuntime.class) {
            if (!closed) {
                cleanups.push(cleanup);
                return;
            }
        }
        try {
            cleanup.run();
        } catch (Throwable failure) {
            log("HOT_RELOAD_LATE_CLEANUP_FAILED", failure);
        }
    }

    /**
     * Host objects that the next generation needs to resume, such as a listener the host already
     * registered. The value is a host or platform object, so the next generation can still call it.
     */
    public static synchronized void carry(String key, Object hostValue) {
        if (hostValue == null) carried.remove(key);
        else carried.put(key, hostValue);
    }

    public static synchronized Object carried(String key) { return carried.get(key); }

    public static synchronized Map<String, Object> carriedState() { return new HashMap<>(carried); }

    public static synchronized void restoreCarried(Map<?, ?> state) {
        if (state == null) return;
        for (Map.Entry<?, ?> entry : state.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() != null) carried.put(key, entry.getValue());
        }
    }

    /** Stops everything this generation started. Later {@link #track} calls clean up at once. */
    public static void close() {
        Runnable[] pending;
        synchronized (HookRuntime.class) {
            closed = true;
            pending = cleanups.toArray(new Runnable[0]);
            cleanups.clear();
            main = null;
        }
        for (Runnable cleanup : pending) {
            try {
                cleanup.run();
            } catch (Throwable failure) {
                log("HOT_RELOAD_CLEANUP_FAILED", failure);
            }
        }
    }
}
