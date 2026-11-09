package com.mynovo.mynovo1;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * หน้าจับคู่ (Pair) สำหรับโหมด DeX
 * - เปิดเป็นหน้าต่างแยกซ้าย และเปิดหน้าตั้งค่าไว้ข้างขวา
 * - พิมพ์รหัส 6 หลักในช่อง แล้วกดสายฟ้า (⚡) เพื่อจับคู่
 * - จับคู่สำเร็จ -> ปิดหน้านี้ แล้วเด้งกลับไปหน้าแอป (หน้ากด Activate)
 *
 * วิธีเรียกจากปุ่ม PAIR:
 *   if (PairingActivity.isDexMode(context)) PairingActivity.launch(context);
 *   else { ...โค้ดเดิมของปุ่ม PAIR... }
 */
public class PairingActivity extends Activity {

    private static final String TAG = "PairingActivity";
    private static final int ORANGE = Color.parseColor("#FFB300");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WirelessActivator activator;
    private int startCount;
    private EditText codeInput;
    private TextView status;
    private boolean finishing;

    // ---------- ตัวช่วยเรียกจากข้างนอก ----------

    public static boolean isDexMode(Context c) {
        try {
            Configuration cfg = c.getResources().getConfiguration();
            try {
                java.lang.reflect.Field f = Configuration.class.getField("semDesktopModeEnabled");
                if (f.getInt(cfg) == 1) return true;
            } catch (Throwable ignored) { }
            if ((cfg.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK) return true;
            if (c instanceof Activity) {
                Display d = SettingsLauncher.displayOf(c);
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "isDexMode failed: " + t);
        }
        return false;
    }

    public static void launch(Context c) {
        Intent i = new Intent(c, PairingActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        Bundle opts = null;
        try {
            Display d = SettingsLauncher.displayOf(c);
            ActivityOptions ao = ActivityOptions.makeBasic();
            ao.setLaunchDisplayId(d.getDisplayId());
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);
            // ครึ่งซ้ายของจอ (ตั้งค่าจะไปอยู่ครึ่งขวา)
            ao.setLaunchBounds(new Rect(Math.round(dm.widthPixels * 0.02f), Math.round(dm.heightPixels * 0.08f),
                    Math.round(dm.widthPixels * 0.50f), Math.round(dm.heightPixels * 0.70f)));
            opts = ao.toBundle();
        } catch (Throwable t) {
            Log.w(TAG, "launch options failed: " + t);
        }
        try {
            if (opts != null) c.startActivity(i, opts); else c.startActivity(i);
        } catch (Throwable t) {
            try { c.startActivity(i); } catch (Throwable t2) { Log.e(TAG, "launch failed", t2); }
        }
    }

    // ---------- หน้าจอ ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View dot(boolean filled) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (filled) g.setColor(ORANGE);
        else { g.setColor(Color.WHITE); g.setStroke(dp(2), ORANGE); }
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        return v;
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(ORANGE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(90), dp(2));
        v.setLayoutParams(lp);
        return v;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // แถบหัวสีส้ม
        TextView header = new TextView(this);
        header.setText("‹  Activation");
        header.setTextColor(Color.WHITE);
        header.setTextSize(16f);
        header.setBackgroundColor(ORANGE);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        header.setOnClickListener(v -> finish());
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        // จุดขั้นตอน
        LinearLayout steps = new LinearLayout(this);
        steps.setGravity(Gravity.CENTER);
        steps.setPadding(0, dp(24), 0, dp(8));
        steps.addView(dot(true));
        steps.addView(line());
        steps.addView(dot(true));
        steps.addView(line());
        steps.addView(dot(false));
        root.addView(steps, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Wireless debugging Pair & Activate");
        title.setTextColor(Color.BLACK);
        title.setTextSize(17f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(20));
        root.addView(title);

        // ช่องรหัส + ปุ่มสายฟ้า
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(20), 0);

        codeInput = new EditText(this);
        codeInput.setHint("Pairing code");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        codeInput.setSingleLine(true);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        codeInput.setTextSize(18f);
        codeInput.setOnEditorActionListener((v, actionId, e) -> { submit(); return true; });
        row.addView(codeInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("⚡");
        go.setTextColor(ORANGE);
        go.setTextSize(26f);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(12), dp(4), dp(4), dp(4));
        go.setOnClickListener(v -> submit());
        row.addView(go);
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.GRAY);
        status.setTextSize(13f);
        status.setPadding(dp(20), dp(10), dp(20), 0);
        root.addView(status);

        TextView help = new TextView(this);
        help.setText("Pair first and then activate.\n"
                + "Settings is open beside this window.\n\n"
                + "1. In Settings, open Developer options > Wireless debugging;\n"
                + "2. Tap Pair device with pairing code;\n"
                + "3. Type the six-digit code above, then tap the lightning button.");
        help.setTextColor(ORANGE);
        help.setTextSize(14f);
        help.setLineSpacing(dp(3), 1f);
        help.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.addView(help);

        setContentView(root);

        // ใช้ตัวจับคู่ตัวเดียวกับหน้า Activate (ถ้ามี) ไม่งั้นสร้างใหม่
        activator = WirelessActivator.current;
        if (activator == null) {
            activator = new WirelessActivator(getApplicationContext());
            activator.start();
        }
        activator.overlayEnabled = false; // หน้านี้มีช่องพิมพ์ของตัวเองแล้ว
        startCount = activator.pairSuccessCount;
        activator.startPairing();

        // เปิดตั้งค่าไว้ข้างๆ (หน่วงนิดให้หน้าต่างนี้วางตำแหน่งเสร็จก่อน)
        handler.postDelayed(() -> SettingsLauncher.openWirelessDebuggingBeside(this), 400);

        handler.post(poll);
    }

    private void submit() {
        String code = codeInput.getText().toString().trim();
        if (code.length() != 6) {
            codeInput.setError("6 digits");
            return;
        }
        status.setText("Pairing...");
        activator.submitPairingCode(code);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (finishing || activator == null) return;
            if (activator.pairSuccessCount > startCount) {
                onPaired();
                return;
            }
            String m = activator.message;
            if (m != null && !m.isEmpty()) status.setText(m);
            handler.postDelayed(this, 300);
        }
    };

    private void onPaired() {
        finishing = true;
        Toast.makeText(this, "Paired successfully", Toast.LENGTH_SHORT).show();
        // กลับไปหน้าแอป (หน้ากด Activate)
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "return to app failed: " + t);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        handler.removeCallbacksAndMessages(null);
        if (activator != null) activator.overlayEnabled = true;
        super.onDestroy();
    }
}
