// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge.notify;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import io.github.jfjdjdhsj.mibandoplusbridge.data.SessionLog;

/**
 * A self-managed incoming call for the protocol test. Watches draw the call from the phone's HFP
 * gateway, so a bare notification packet is not enough: Telecom has to report a ringing call
 * before the watch shows the answer/reject screen.
 */
public final class TestCallService extends ConnectionService {
    private static final String ACCOUNT_ID = "oplusband-test-call";
    private static final String TEST_NUMBER = "13800138000";
    private static volatile Connection active;

    public static final class Failure extends IllegalStateException {
        public Failure(String message) { super(message); }
    }

    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle account,
                                                           ConnectionRequest request) {
        Connection connection = new Connection() {
            @Override public void onAnswer() {
                setActive();
                SessionLog.line(TestCallService.this, "TEST_CALL answered");
            }

            @Override public void onReject() {
                setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
                destroy();
                active = null;
                SessionLog.line(TestCallService.this, "TEST_CALL rejected");
            }

            @Override public void onDisconnect() {
                setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                destroy();
                active = null;
                SessionLog.line(TestCallService.this, "TEST_CALL disconnected");
            }

            @Override public void onAbort() {
                setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
                destroy();
                active = null;
            }
        };
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setAudioModeIsVoip(false);
        connection.setAddress(Uri.fromParts("tel", TEST_NUMBER, null), TelecomManager.PRESENTATION_ALLOWED);
        String name = request == null || request.getExtras() == null
                ? "" : request.getExtras().getString("oplusband.caller", "");
        connection.setCallerDisplayName(name == null || name.isBlank() ? "测试来电" : name,
                TelecomManager.PRESENTATION_ALLOWED);
        connection.setInitializing();
        connection.setInitialized();
        connection.setRinging();
        active = connection;
        SessionLog.line(this, "TEST_CALL ringing");
        return connection;
    }

    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle account,
                                                           ConnectionRequest request) {
        active = null;
        SessionLog.line(this, "TEST_CALL failed");
    }

    /** Registers the self-managed account and asks Telecom to start ringing. */
    public static void start(Context context, String caller) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        TelecomManager telecom = app.getSystemService(TelecomManager.class);
        if (telecom == null) throw new Failure("TEST_CALL_UNAVAILABLE");
        PhoneAccountHandle handle = new PhoneAccountHandle(
                new ComponentName(app, TestCallService.class), ACCOUNT_ID);
        PhoneAccount account = PhoneAccount.builder(handle, "桥接测试来电")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build();
        try {
            telecom.registerPhoneAccount(account);
        } catch (RuntimeException denied) {
            throw new Failure("TEST_CALL_ACCOUNT");
        }
        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("tel", TEST_NUMBER, null));
        Bundle callExtras = new Bundle();
        callExtras.putString("oplusband.caller", caller == null ? "测试来电" : caller);
        extras.putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, callExtras);
        try {
            telecom.addNewIncomingCall(handle, extras);
        } catch (RuntimeException denied) {
            throw new Failure("TEST_CALL_REJECTED");
        }
    }

    /** Drops the ringing or answered test call. Safe when nothing is ringing. */
    public static void stop() {
        Connection connection = active;
        active = null;
        if (connection == null) return;
        try {
            connection.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
            connection.destroy();
        } catch (RuntimeException ignored) { }
    }

    public static boolean ringing() { return active != null; }
}
