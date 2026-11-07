// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.jfjdjdhsj.mibandoplusbridge;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import io.github.jfjdjdhsj.mibandoplusbridge.data.ChinaTime;
import io.github.jfjdjdhsj.mibandoplusbridge.data.HealthSyncSettings;
import io.github.jfjdjdhsj.mibandoplusbridge.data.LocalPrefs;
import io.github.jfjdjdhsj.mibandoplusbridge.ui.BridgeScreen;

/** Schedule for pulling health history off the band. */
public final class HealthSyncActivity extends AppCompatActivity {
    private BridgeScreen screen;
    private TextView intervalStatus;
    private TextView lastSync;
    private MaterialSwitch autoSync;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen = BridgeScreen.attach(this, "健康同步", true);
        LinearLayout auto = screen.section("自动同步");
        autoSync = screen.switchRow(auto, "自动同步健康数据",
                "连接期间按设定间隔从手环拉取健康数据。关闭后只在点「立即同步」或打开 App 时同步。");
        final boolean[] writingAuto = {false};
        autoSync.setChecked(HealthSyncSettings.enabled(this));
        autoSync.setOnCheckedChangeListener((button, checked) -> {
            if (writingAuto[0]) return;
            if (HealthSyncSettings.setEnabled(this, checked)) {
                refreshInterval();
                return;
            }
            writingAuto[0] = true;
            button.setChecked(!checked);
            writingAuto[0] = false;
            intervalStatus.setText("开关状态保存中断，再拨一次即可。");
        });
        intervalStatus = screen.bodyText(auto, "");
        screen.textButton(auto, "设置同步间隔", this::editInterval);
        screen.caption(auto, "自动同步完成后会在通知栏显示同步时间。");

        LinearLayout open = screen.section("打开 App 时");
        MaterialSwitch onOpen = screen.switchRow(open, "打开 App 立即同步",
                "每次打开 App 时先同步一次，5 分钟内重复打开只同步一次。");
        final boolean[] writingOpen = {false};
        onOpen.setChecked(HealthSyncSettings.syncOnOpen(this));
        onOpen.setOnCheckedChangeListener((button, checked) -> {
            if (writingOpen[0]) return;
            if (HealthSyncSettings.setSyncOnOpen(this, checked)) return;
            writingOpen[0] = true;
            button.setChecked(!checked);
            writingOpen[0] = false;
        });

        LinearLayout last = screen.section("上次同步");
        lastSync = screen.bodyText(last, "");
        refreshInterval();
        refreshLast();
    }

    @Override protected void onResume() {
        super.onResume();
        if (intervalStatus != null) refreshInterval();
        if (lastSync != null) refreshLast();
    }

    private void refreshInterval() {
        boolean on = HealthSyncSettings.enabled(this);
        if (autoSync != null && autoSync.isChecked() != on) autoSync.setChecked(on);
        intervalStatus.setText((on ? "自动同步已开启，当前间隔：" : "自动同步已关闭，原间隔：")
                + intervalText(HealthSyncSettings.intervalMinutes(this)));
    }

    private void refreshLast() {
        long at = LocalPrefs.open(this, "band-state").getLong("lastSyncAtMs", 0);
        lastSync.setText(at > 0
                ? "上次同步：" + ChinaTime.dateTime(at)
                : "连接手环后点「立即同步」，即可完成第一次同步。");
    }

    /** Days, hours and minutes are all accepted so 1 天 and 90 分钟 are equally expressible. */
    private static String intervalText(long minutes) {
        if (minutes >= 24 * 60) {
            long days = minutes / (24 * 60), rest = minutes % (24 * 60);
            if (rest == 0) return days + " 天";
            return days + " 天 " + intervalText(rest);
        }
        if (minutes >= 60) {
            long hours = minutes / 60, rest = minutes % 60;
            return rest == 0 ? hours + " 小时" : hours + " 小时 " + rest + " 分钟";
        }
        return minutes + " 分钟";
    }

    private void editInterval() {
        long current = HealthSyncSettings.intervalMinutes(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, 0);
        android.widget.EditText days = field(form, "天", current / (24 * 60));
        android.widget.EditText hours = field(form, "小时", current % (24 * 60) / 60);
        android.widget.EditText minutes = field(form, "分钟", current % 60);
        new MaterialAlertDialogBuilder(this)
                .setTitle("健康同步间隔")
                .setMessage("最少 " + HealthSyncSettings.MIN_INTERVAL_MINUTES + " 分钟，最长 "
                        + (HealthSyncSettings.MAX_INTERVAL_MINUTES / (24 * 60)) + " 天。留空按 0 计算。")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, ignored) -> {
                    long total = value(days) * 24 * 60 + value(hours) * 60 + value(minutes);
                    long saved = Math.max(HealthSyncSettings.MIN_INTERVAL_MINUTES,
                            Math.min(HealthSyncSettings.MAX_INTERVAL_MINUTES, total));
                    boolean stored = HealthSyncSettings.setIntervalMinutes(this, saved);
                    refreshInterval();
                    intervalStatus.setText((stored ? "" : "间隔保存中断，再设一次。")
                            + (saved == total ? "" : "间隔超出范围，已设为每 " + intervalText(saved) + "。")
                            + (saved == total && stored ? "已设为每 " + intervalText(saved) + "。" : ""));
                })
                .show();
    }

    private android.widget.EditText field(LinearLayout parent, String hint, long initial) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(initial == 0 ? "" : String.valueOf(initial));
        parent.addView(input);
        return input;
    }

    private static long value(android.widget.EditText input) {
        try {
            return Long.parseLong(input.getText().toString().trim());
        } catch (NumberFormatException blank) {
            return 0;
        }
    }
}
