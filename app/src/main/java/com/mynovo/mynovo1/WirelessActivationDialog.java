package com.mynovo.mynovo1;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

public class WirelessActivationDialog {

    private static WirelessActivator activator;

    private final Activity act;
    private final Runnable onChanged;
    private final WirelessActivator a;
    private final Handler h = new Handler(Looper.getMainLooper());
    private AlertDialog dlg;
    private TextView tvSteps, tvStatus, btnActivate;
    private boolean lastActivated;

    public static WirelessActivationDialog show(Activity act, Runnable onChanged) {
        WirelessActivationDialog d = new WirelessActivationDialog(act, onChanged);
        d.build();
        return d;
    }

    private WirelessActivationDialog(Activity act, Runnable onChanged) {
        this.act = act;
        this.onChanged = onChanged;
        if (activator == null) activator = new WirelessActivator(act);
        this.a = activator;
    }

    public Dialog getDialog() { return dlg; }

    public boolean isShowing() { return dlg != null && dlg.isShowing(); }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void build() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 24, 32, 24);
        box.setBackgroundColor(Color.parseColor("#1E2230"));

        TextView title = text("Wireless activation", 18f, Color.parseColor("#8BE9FD"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 16);
        box.addView(title);

        tvSteps = text("1. Developer Options\n2. USB debugging\n3. Wireless debugging\n"
                + "4. Pair (notification: Enter code)\n5. Wait for port automatically\n6. Tap to Activate",
                13f, Color.WHITE);
        tvSteps.setPadding(0, 0, 0, 16);
        box.addView(tvSteps);

        tvStatus = text("", 12f, Color.parseColor("#FFC857"));
        tvStatus.setPadding(0, 0, 0, 16);
        box.addView(tvStatus);

        btnActivate = text("ACTIVATE", 12f, Color.WHITE);
        btnActivate.setTypeface(null, android.graphics.Typeface.BOLD);
        btnActivate.setPadding(28, 14, 28, 14);
        btnActivate.setOnClickListener(v -> {
            if (a.connected && !a.activated) a.activate();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        box.addView(btnActivate, lp);

        dlg = new AlertDialog.Builder(act).setView(box).create();
        if (dlg.getWindow() != null)
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        dlg.setOnDismissListener(d -> {
            h.removeCallbacksAndMessages(null);
            a.stop();
        });

        // เริ่มหา mDNS + เริ่ม flow pair
        a.start();
        if (!a.paired) {
            a.startPairing();
            if (WirelessActivator.wirelessDebuggingOn(act)) {
                try {
                    act.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Throwable ignored) { }
            }
        }

        dlg.show();
        h.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isShowing()) return;
            refresh();
            h.postDelayed(this, 500);
        }
    };

    /** MainActivity เรียกตอน refresh — อัปเดตข้อความในที่เดิม ไม่เปิดใหม่ */
    public void refresh() {
        if (dlg == null) return;
        String s;
        if (a.activated) s = "Activated";
        else if (!WirelessActivator.devOptionsOn(act)) s = "Turn on Developer options";
        else if (!WirelessActivator.usbDebuggingOn(act)) s = "Turn on USB debugging";
        else if (!WirelessActivator.wirelessDebuggingOn(act)) s = "Turn on Wireless debugging";
        else if (a.connected) s = "Ready — tap ACTIVATE";
        else if (a.paired) s = "Paired — waiting for port...";
        else s = a.message.isEmpty()
                ? "Open \"Pair device with pairing code\", then type the code in the notification"
                : a.message;
        tvStatus.setText(s);

        boolean ready = a.connected && !a.activated;
        btnActivate.setBackgroundColor(Color.parseColor(ready ? "#BD93F9" : "#44475A"));

        if (a.activated && !lastActivated) {
            lastActivated = true;
            if (onChanged != null) onChanged.run();
            dlg.dismiss();
        }
    }
}