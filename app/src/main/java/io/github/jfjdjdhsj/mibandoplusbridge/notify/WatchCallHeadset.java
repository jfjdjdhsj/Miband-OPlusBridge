// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.notify;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import io.github.jfjdjdhsj.mibandoplusbridge.data.LocalPrefs;
import io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog;
import java.lang.reflect.Method;

/** Phone-side HFP gateway for watch-form devices. Bands never reach this. */
public final class WatchCallHeadset {
    private static final Object LOCK = new Object();
    private static Context app;
    private static BluetoothAdapter adapter;
    private static BluetoothHeadset headset;
    private static WatchCallRoute.Link wanted = WatchCallRoute.Link.NONE;
    private static boolean receiverRegistered;
    private static boolean proxyRequested;

    private static final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HEADSET) return;
            synchronized (LOCK) {
                headset = (BluetoothHeadset) proxy;
                applyNow();
            }
        }

        @Override public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HEADSET) return;
            synchronized (LOCK) { headset = null; }
        }
    };

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            synchronized (LOCK) { applyNow(); }
        }
    };

    private WatchCallHeadset() {}

    public static void apply(Context context, WatchCallRoute.Link link) {
        if (context == null || link == null) return;
        synchronized (LOCK) {
            Context application = context.getApplicationContext();
            app = application == null ? context : application;
            wanted = link;
            SessionLog.line(app, "CALL_HFP " + link);
            if (app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                SessionLog.line(app, "CALL_HFP denied");
                return;
            }
            if (headset == null) {
                ensureProxy();
                return;
            }
            applyNow();
        }
    }

    private static void ensureProxy() {
        if (proxyRequested) return;
        BluetoothManager manager = app.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            SessionLog.line(app, "CALL_HFP unavailable");
            return;
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        }
        proxyRequested = adapter.getProfileProxy(app, listener, BluetoothProfile.HEADSET);
        if (!proxyRequested) SessionLog.line(app, "CALL_HFP unavailable");
    }

    private static void applyNow() {
        if (app == null || headset == null) return;
        String mac = LocalPrefs.open(app, "band-state").getString("mac", "");
        if (mac == null || mac.isBlank()) {
            SessionLog.line(app, "CALL_HFP no-device");
            return;
        }
        BluetoothDevice device;
        try {
            if (adapter == null) {
                BluetoothManager manager = app.getSystemService(BluetoothManager.class);
                adapter = manager == null ? null : manager.getAdapter();
            }
            if (adapter == null) return;
            device = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException bad) {
            SessionLog.line(app, "CALL_HFP no-device");
            return;
        }
        try {
            invoke("setActiveDevice", new Class<?>[] {BluetoothDevice.class},
                    wanted == WatchCallRoute.Link.NONE ? new Object[] {null} : new Object[] {device});
            int state = headset.getConnectionState(device);
            if (wanted == WatchCallRoute.Link.NONE) {
                if (headset.isAudioConnected(device)) invoke("disconnectAudio", new Class<?>[] {}, new Object[] {});
                SessionLog.line(app, "CALL_HFP audio=release state=" + state);
                return;
            }
            if (state != BluetoothProfile.STATE_CONNECTED) {
                boolean started = Boolean.TRUE.equals(
                        invoke("connect", new Class<?>[] {BluetoothDevice.class}, new Object[] {device}));
                SessionLog.line(app, "CALL_HFP profile=" + started + " state=" + state);
                return;
            }
            if (wanted == WatchCallRoute.Link.HANDSET && !headset.isAudioConnected(device)) {
                boolean audio = Boolean.TRUE.equals(invoke("connectAudio", new Class<?>[] {}, new Object[] {}));
                SessionLog.line(app, "CALL_HFP audio=" + audio);
                return;
            }
            SessionLog.line(app, "CALL_HFP profile=up audio=" + headset.isAudioConnected(device));
        } catch (SecurityException denied) {
            SessionLog.line(app, "CALL_HFP denied");
        } catch (RuntimeException failure) {
            SessionLog.line(app, "CALL_HFP failed " + failure.getClass().getSimpleName());
        }
    }

    /** These gateway methods are hidden. The phone asks the watch to accept HFP, then moves SCO. */
    private static Object invoke(String name, Class<?>[] types, Object[] args) {
        try {
            Method method = BluetoothHeadset.class.getMethod(name, types);
            return method.invoke(headset, args);
        } catch (ReflectiveOperationException failure) {
            SessionLog.line(app, "CALL_HFP " + name + "-unavailable");
            return Boolean.FALSE;
        }
    }
}
