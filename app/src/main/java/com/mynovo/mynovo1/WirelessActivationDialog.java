package com.mynovo.mynovo1;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

public class WirelessActivationDialog {

    private static final int FRAME = Color.parseColor("#DCDCF8");
    private static final int BG = Color.parseColor("#5B5FB5");
    private static final int ROW = Color.parseColor("#6F78E8");
    private static final int BTN = Color.parseColor("#2E3A8C");
    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int SUB = Color.parseColor("#E3E6FF");

    // TODO: ใส่ลิงก์วิดีโอสอนของคุณ
    private static final String TUTORIAL_URL = "https://example.com/tutorial";

    private final Activity act;
    private final AlertDialog dialog;
    private final WirelessActivator wa;
    private final Runnable onChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Row[] rows = new Row[6];
    private final TextView status;
    private boolean notified;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    public static WirelessActivationDialog show(Activity activity, Runnable onChanged) {
        WirelessActivationDialog d = new WirelessActivationDialog(activity, onChanged);
        d.start();
        return d;
    }

    private WirelessActivationDialog(Activity activity, Runnable onChanged) {
        this.act = activity;
        this.onChanged = onChanged;
        this.wa = new WirelessActivator(activity);

        // กรอบนอก (สีลาเวนเดอร์)
        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(FRAME);
        outer.setPadding(dp(14), dp(10), dp(14), dp(14));

        // หัวข้อ + ปุ่มปิด
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, 0, dp(10));

        TextView title = new TextView(activity);
        title.setText("Wireless activation");
        title.setTextColor(Color.parseColor("#1A1A2E"));
        title.setTextSize(20);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD_ITALIC);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(activity);
        close.setText("\u2715");
        close.setTextColor(Color.WHITE);
        close.setTextSize(14);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setBackground(oval(BTN));
        close.setOnClickListener(v -> dismissDialog()); // แก้: เรียกผ่านเมธอด
        header.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));
        outer.addView(header);

        // เนื้อหา (สีม่วง)
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(BG);
        body.setPadding(dp(12), dp(12), dp(12), dp(12));
        outer.addView(body);

        String pairHelp = "กด PAIR แล้วไปที่ Wireless debugging > Pair device with pairing code "
                + "จากนั้นกรอกรหัสในการแจ้งเตือนของแอป";
        String waitHelp = "แอปจะค้นหาพอร์ตเชื่อมต่อเอง โปรดเปิด Wireless debugging "
                + "และเชื่อมต่อ Wi-Fi ค้างไว้";

        rows[0] = new Row("1. Turn On \"Developer Options\" from System Settings", null, null);
        rows[1] = new Row("2. Turn On \"USB debugging\" from Developer Options", null, null);
        rows[2] = new Row("3. Turn on Wireless debugging",
                "If this option doesn't detect correctly, please re-enable \"wireless debugging\" option",
                null);
        rows[3] = new Row("4. Navigate to Settings to Pair/Connect", null, pairHelp);
        rows[4] = new Row("5. Wait For the Port Being Opened Automatically", null, waitHelp);
        rows[5] = new Row("6. Tap to Activate", null, null);
        for (Row r : rows) body.addView(r.view);

        // แถวลิงก์สอนใช้งาน
        LinearLayout tut = new LinearLayout(activity);
        tut.setBackgroundColor(ROW);
        tut.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tut.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView link = new TextView(activity);
        link.setText("Click me to watch tutorial");
        link.setTextColor(Color.WHITE);
        link.setTextSize(13);
        link.setPaintFlags(link.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        link.setOnClickListener(v -> {
            try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_URL))); }
            catch (Exception ignored) { }
        });
        tut.addView(link);
        body.addView(tut, rowParams());

        status = new TextView(activity);
        status.setTextColor(Color.parseColor("#FFD54F"));
        status.setTextSize(12);
        status.setPadding(dp(4), dp(8), dp(4), 0);
        body.addView(status);

        dialog = new AlertDialog.Builder(activity).setView(outer).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(FRAME));
        }
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(poll);
            wa.stop();
        });
    }

    private void start() {
        if (Build.VERSION.SDK_INT >= 33 && act.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
        wa.start();
        dialog.show();
        refresh();
        handler.postDelayed(poll, 1000);
    }

    // แก้: เมธอดใหม่ ใช้แทน dialog.dismiss() ตรงๆ ใน lambda ของ constructor
    private void dismissDialog() {
        dialog.dismiss();
    }

    public AlertDialog getDialog() { return dialog; }

    public boolean isShowing() { return dialog.isShowing(); }

    public void refresh() {
        boolean wirelessOn = WirelessActivator.wirelessDebuggingOn(act);

        rows[0].set(WirelessActivator.devOptionsOn(act), "OPEN", true, v -> openDeviceInfo());
        rows[1].set(WirelessActivator.usbDebuggingOn(act), "OPEN", true, v -> openDevSettings());
        rows[2].set(wirelessOn, "OPEN", true, v -> openDevSettings());

        rows[3].set(wa.paired || wa.connected, "PAIR", wirelessOn, v -> {
            wa.startPairing();
            openDevSettings();
        });

        rows[4].set(wa.connected, "WAITING", false, null);

        rows[5].set(wa.activated || ShellServerClient.isConnected(), "ACTIVATE",
                wa.connected, v -> wa.activate());

        status.setText(wa.message);

        if (wa.activated && !notified) {
            notified = true;
            if (onChanged != null) onChanged.run();
            handler.postDelayed(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            }, 900);
        }
    }

    private void openDeviceInfo() {
        try { act.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); }
        catch (Exception e) { act.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void openDevSettings() {
        try { act.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
        catch (Exception e) { act.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(18));
        g.setColor(color);
        return g;
    }

    private class Row {
        final LinearLayout view;
        final TextView check;
        final TextView help;
        final Button button;

        Row(String text, String sub, String helpText) {
            view = new LinearLayout(act);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setBackgroundColor(ROW);
            view.setPadding(dp(12), dp(8), dp(10), dp(8));
            view.setLayoutParams(rowParams());

            LinearLayout left = new LinearLayout(act);
            left.setOrientation(LinearLayout.VERTICAL);

            TextView t = new TextView(act);
            t.setText(text);
            t.setTextColor(Color.WHITE);
            t.setTextSize(14);
            left.addView(t);

            if (sub != null) {
                TextView s = new TextView(act);
                s.setText(sub);
                s.setTextColor(SUB);
                s.setTextSize(11);
                s.setPadding(0, dp(2), 0, 0);
                left.addView(s);
            }
            view.addView(left, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // ปุ่ม ? (เฉพาะแถวที่มีคำอธิบาย)
            help = new TextView(act);
            help.setText("?");
            help.setTextColor(ROW);
            help.setTextSize(12);
            help.setTypeface(Typeface.DEFAULT_BOLD);
            help.setGravity(Gravity.CENTER);
            help.setBackground(oval(Color.WHITE));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(20), dp(20));
            hp.rightMargin = dp(8);
            hp.leftMargin = dp(8);
            view.addView(help, hp);
            if (helpText != null) {
                help.setOnClickListener(v -> new AlertDialog.Builder(act)
                        .setMessage(helpText)
                        .setPositiveButton("OK", null)
                        .show());
            } else {
                help.setVisibility(View.GONE);
            }

            check = new TextView(act);
            check.setText("\u2713");
            check.setTextColor(GREEN);
            check.setTextSize(22);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setPadding(dp(14), 0, dp(14), 0);
            view.addView(check);

            button = new Button(act);
            button.setTextColor(Color.WHITE);
            button.setTextSize(12);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setBackground(pill(BTN));
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(dp(34));
            button.setPadding(dp(20), 0, dp(20), 0);
            view.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));

            helpAvailable = helpText != null;
        }

        private boolean helpAvailable;

        void set(boolean done, String label, boolean enabled, View.OnClickListener click) {
            check.setVisibility(done ? View.VISIBLE : View.GONE);
            button.setVisibility(done ? View.GONE : View.VISIBLE);
            if (helpAvailable) help.setVisibility(done ? View.GONE : View.VISIBLE);
            button.setText(label);
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.7f);
            button.setOnClickListener(click);
        }
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
