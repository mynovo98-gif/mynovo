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
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * Wireless activation wizard
 * - โหมดปกติ: ปุ่ม PAIR ทำแบบเดิม (เปิดหน้าตั้งค่า)
 * - โหมด DeX: ปุ่ม PAIR "สลับหน้า" ในหน้าต่างเดียวกันเป็นหน้าพิมพ์รหัสจับคู่
 *   ส่วนแอปตั้งค่าเปิดแยกเป็นอีกหน้าต่างข้างๆ แบบเดิม
 *   จับคู่สำเร็จ -> สลับกลับมาหน้าขั้นตอนอัตโนมัติ
 */
public class WirelessActivationDialog {

    private static final int FRAME = Color.parseColor("#1E2230");
    private static final int BG = Color.parseColor("#1E2230");
    private static final int ROW = Color.parseColor("#282C3E");
    private static final int BTN = Color.parseColor("#BD93F9");
    private static final int GREEN = Color.parseColor("#50FA7B");
    private static final int SUB = Color.parseColor("#9AA0B5");
    private static final int ORANGE = Color.parseColor("#FFB300");

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

    // หน้าต่างๆ ในหน้าต่างเดียว
    private final LinearLayout outer;
    private final LinearLayout header;
    private final LinearLayout stepsView;
    private LinearLayout pairView;
    private EditText codeInput;
    private TextView pairStatus;
    private boolean pairing;
    private int pairStartCount;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    // ตรวจผลจับคู่เร็วๆ ตอนอยู่หน้าพิมพ์รหัส
    private final Runnable pairPoll = new Runnable() {
        @Override public void run() {
            if (!pairing) return;
            if (wa.pairSuccessCount > pairStartCount) {
                onPaired();
                return;
            }
            String m = wa.message;
            if (m != null && !m.isEmpty()) pairStatus.setText(m);
            handler.postDelayed(this, 300);
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

        // กรอบนอก
        outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(FRAME);
        outer.setPadding(dp(14), dp(10), dp(14), dp(14));

        // หัวข้อ + ปุ่มปิด
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, 0, dp(10));

        TextView title = new TextView(activity);
        title.setText("Wireless activation");
        title.setTextColor(Color.parseColor("#8BE9FD"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(activity);
        close.setText("\u2715");
        close.setTextColor(Color.WHITE);
        close.setTextSize(14);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setBackground(oval(BTN));
        close.setOnClickListener(v -> dismissDialog());
        header.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));
        outer.addView(header);

        // หน้าขั้นตอน 1-6
        stepsView = new LinearLayout(activity);
        stepsView.setOrientation(LinearLayout.VERTICAL);
        stepsView.setBackgroundColor(BG);
        stepsView.setPadding(dp(4), dp(4), dp(4), dp(4));
        outer.addView(stepsView);

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
        for (Row r : rows) stepsView.addView(r.view);

        // แถวลิงก์สอนใช้งาน
        LinearLayout tut = new LinearLayout(activity);
        tut.setBackgroundColor(ROW);
        tut.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tut.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView link = new TextView(activity);
        link.setText("Click me to watch tutorial");
        link.setTextColor(Color.parseColor("#8BE9FD"));
        link.setTextSize(13);
        link.setPaintFlags(link.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        link.setOnClickListener(v -> {
            try { act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_URL))); }
            catch (Exception ignored) { }
        });
        tut.addView(link);
        stepsView.addView(tut, rowParams());

        status = new TextView(activity);
        status.setTextColor(Color.parseColor("#FFD54F"));
        status.setTextSize(12);
        status.setPadding(dp(4), dp(8), dp(4), 0);
        stepsView.addView(status);

        dialog = new AlertDialog.Builder(activity).setView(outer).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(FRAME));
        }
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(poll);
            handler.removeCallbacks(pairPoll);
            pairing = false;
            wa.overlayEnabled = true;
            wa.stop();
        });
    }

    private void start() {
        if (Build.VERSION.SDK_INT >= 33) {
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            if (act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (act.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (!need.isEmpty())
                act.requestPermissions(need.toArray(new String[0]), 77);
        }
        wa.start();
        dialog.show();
        refresh();
        handler.postDelayed(poll, 1000);
    }

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

        rows[3].set(wa.paired || wa.connected, "PAIR", wirelessOn, v -> onPairClicked());

        rows[4].set(wa.connected, "WAITING", false, null);

        rows[5].set(wa.activated || ShellServerClient.isConnected(), "ACTIVATE",
                wa.connected, v -> wa.activate());

        if (!pairing) status.setText(wa.message);

        if (wa.activated && !notified) {
            notified = true;
            if (onChanged != null) onChanged.run();
            handler.postDelayed(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            }, 900);
        }
    }

    // ---------- ปุ่ม PAIR ----------

    // ตรวจ DeX แบบกว้างขึ้น: reflection ของ Samsung มักโดนบล็อก เลยเช็กหน้าต่างลอย/แยกจอด้วย
    private boolean isDex() {
        try {
            if (PairingActivity.isDexMode(act)) return true;
            if (Build.VERSION.SDK_INT >= 24 && act.isInMultiWindowMode()) return true;
            String cfg = act.getResources().getConfiguration().toString().toLowerCase();
            if (cfg.contains("freeform") || cfg.contains("desktopmodeenabled/1")) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private void onPairClicked() {
        if (isDex()) {
            showPairingPage();
        } else {
            wa.startPairing();
            openDevSettings();
        }
    }

    // สลับเป็นหน้าพิมพ์รหัส (หน้าต่างเดียวกัน) + เปิดตั้งค่าแยกข้างๆ
    private void showPairingPage() {
        if (pairView == null) pairView = buildPairView();

        pairing = true;
        wa.overlayEnabled = false; // หน้านี้มีช่องพิมพ์ของตัวเองแล้ว
        pairStartCount = wa.pairSuccessCount;
        wa.startPairing();

        header.setVisibility(View.GONE);
        stepsView.setVisibility(View.GONE);
        outer.setPadding(0, 0, 0, 0);
        if (pairView.getParent() == null) outer.addView(pairView);
        pairView.setVisibility(View.VISIBLE);

        pairStatus.setText("");
        codeInput.setText("");
        codeInput.requestFocus();

        // เปิดตั้งค่าแยกหน้าต่างข้างๆ แบบเดิม (หน่วงนิดให้หน้านี้วางเสร็จก่อน)
        handler.postDelayed(() -> SettingsLauncher.openWirelessDebuggingBeside(act), 400);

        handler.removeCallbacks(pairPoll);
        handler.post(pairPoll);
    }

    // สลับกลับหน้าขั้นตอน
    private void showStepsPage() {
        pairing = false;
        handler.removeCallbacks(pairPoll);
        wa.overlayEnabled = true;
        if (pairView != null) pairView.setVisibility(View.GONE);
        header.setVisibility(View.VISIBLE);
        stepsView.setVisibility(View.VISIBLE);
        outer.setPadding(dp(14), dp(10), dp(14), dp(14));
        refresh();
    }

    private void onPaired() {
        Toast.makeText(act, "Paired successfully", Toast.LENGTH_SHORT).show();
        showStepsPage();
    }

    private void submitCode() {
        String code = codeInput.getText().toString().trim();
        if (code.length() != 6) {
            codeInput.setError("6 digits");
            return;
        }
        pairStatus.setText("Pairing...");
        wa.submitPairingCode(code);
    }

    private LinearLayout buildPairView() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(10), dp(14), dp(14));

        // หัวข้อ + ปุ่มย้อนกลับ (สไตล์เดียวกับหน้าขั้นตอน)
        LinearLayout hd = new LinearLayout(act);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.setPadding(dp(2), 0, 0, dp(10));

        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextColor(Color.WHITE);
        back.setTextSize(20);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setGravity(Gravity.CENTER);
        back.setBackground(oval(BTN));
        back.setOnClickListener(v -> showStepsPage());
        hd.addView(back, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView title = new TextView(act);
        title.setText("Pair device");
        title.setTextColor(Color.parseColor("#8BE9FD"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        hd.addView(title, tp);
        root.addView(hd);

        // แถวช่องรหัส + ปุ่ม ⚡
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(ROW);
        row.setPadding(dp(12), dp(6), dp(10), dp(6));

        codeInput = new EditText(act);
        codeInput.setHint("Pairing code");
        codeInput.setTextColor(Color.WHITE);
        codeInput.setHintTextColor(SUB);
        codeInput.setBackground(null);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        codeInput.setSingleLine(true);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setTextSize(18f);
        codeInput.setOnEditorActionListener((v, actionId, e) -> { submitCode(); return true; });
        row.addView(codeInput, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(act);
        go.setText("\u26A1");
        go.setTextColor(Color.WHITE);
        go.setTextSize(18f);
        go.setGravity(Gravity.CENTER);
        go.setBackground(pill(BTN));
        go.setOnClickListener(v -> submitCode());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(56), dp(34));
        gp.leftMargin = dp(8);
        row.addView(go, gp);
        root.addView(row, rowParams());

        pairStatus = new TextView(act);
        pairStatus.setTextColor(Color.parseColor("#FFD54F"));
        pairStatus.setTextSize(12);
        pairStatus.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(pairStatus);

        return root;
    }

    // ---------- ตัวช่วยเดิม ----------

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
        private final boolean helpAvailable;

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
