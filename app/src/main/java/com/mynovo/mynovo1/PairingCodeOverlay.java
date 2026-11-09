package com.mynovo.mynovo1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * หน้าต่างลอย (overlay) สำหรับพิมพ์รหัสจับคู่ 6 หลักของ Wireless debugging
 * ใช้แทนช่องตอบกลับในแจ้งเตือน ซึ่งใช้ไม่ได้ใน DeX / หน้าต่างลอย
 *
 * วิธีใช้ (ตอนกดปุ่ม Pair):
 *   PairingCodeOverlay.show(context, code -> {
 *       // เรียกตัวจับคู่เดิมของคุณ เช่น adbManager.pair(host, port, code)
 *   });
 */
public class PairingCodeOverlay {

    public interface OnCode { void onCode(String code); }

    private static View current;
    private static WindowManager currentWm;

    public static void show(Context base, OnCode callback) {
        dismiss();
        try {
            // เลือกจอภายนอก (DeX) ถ้ามี ไม่งั้นใช้จอหลัก
            DisplayManager dm = (DisplayManager) base.getSystemService(Context.DISPLAY_SERVICE);
            Display target = dm.getDisplay(Display.DEFAULT_DISPLAY);
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY
                        && (d.getFlags() & Display.FLAG_PRIVATE) == 0
                        && d.getState() == Display.STATE_ON) { target = d; break; }
            }
            Context dctx = base.createDisplayContext(target);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            if (Build.VERSION.SDK_INT >= 30) {
                try { dctx = dctx.createWindowContext(type, null); } catch (Throwable ignored) {}
            }
            final WindowManager wm = (WindowManager) dctx.getSystemService(Context.WINDOW_SERVICE);
            final float dp = dctx.getResources().getDisplayMetrics().density;

            LinearLayout box = new LinearLayout(dctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(20 * dp);
            box.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#1E2235"));
            bg.setCornerRadius(20 * dp);
            bg.setStroke(Math.round(dp), Color.parseColor("#3B4261"));
            box.setBackground(bg);

            TextView title = new TextView(dctx);
            title.setText("พิมพ์รหัสจับคู่ 6 หลัก");
            title.setTextColor(Color.parseColor("#8BE9FD"));
            title.setTextSize(16f);
            box.addView(title);

            final EditText input = new EditText(dctx);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setHint("______");
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(Color.GRAY);
            input.setTextSize(24f);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            box.addView(input);

            LinearLayout row = new LinearLayout(dctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);

            Button cancel = new Button(dctx);
            cancel.setText("ยกเลิก");
            cancel.setOnClickListener(v -> dismiss());
            row.addView(cancel);

            Button ok = new Button(dctx);
            ok.setText("จับคู่");
            ok.setOnClickListener(v -> {
                String code = input.getText().toString().trim();
                if (code.length() == 6) {
                    dismiss();
                    if (callback != null) callback.onCode(code);
                } else {
                    input.setError("ต้องเป็นเลข 6 หลัก");
                }
            });
            row.addView(ok);
            box.addView(row);

            // ไม่ใส่ FLAG_NOT_FOCUSABLE เพื่อให้ช่องพิมพ์รับคีย์บอร์ดได้
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(320 * dp), WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(40 * dp);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

            wm.addView(box, lp);
            current = box;
            currentWm = wm;
            input.requestFocus();
        } catch (Throwable t) {
            android.util.Log.e("PAIRCODE", "show failed", t);
        }
    }

    public static boolean isShowing() {
        return current != null && current.getParent() != null;
    }

    public static void dismiss() {
        try {
            if (current != null && currentWm != null && current.getParent() != null) {
                currentWm.removeView(current);
            }
        } catch (Throwable ignored) {}
        current = null;
        currentWm = null;
    }
}
