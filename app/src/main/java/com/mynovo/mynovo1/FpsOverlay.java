package com.mynovo.mynovo1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * กล่อง FPS เล็ก ๆ พื้นดำ ตัวอักษรขาว เช่น "FPS 90" ล็อกไว้มุมซ้ายล่างของจอ
 * - แตะทะลุได้ (ไม่รับทัช/โฟกัส) จึงไม่รบกวนเกมและ TouchSimulator
 * - แสดงตัวเลขเฉพาะเมื่อ source == "surface" (วัดจากเฟรมจริง) ไม่เช่นนั้นแสดง "FPS --"
 */
public class FpsOverlay {

    private final Context ctx;
    private final WindowManager wm;
    private TextView tv;
    private WindowManager.LayoutParams lp;
    private boolean showing = false;

    public FpsOverlay(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
    }

    public boolean isShowing() {
        return showing;
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    public void show() {
        if (showing || wm == null) return;
        try {
            tv = new TextView(ctx);
            tv.setText("FPS --");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tv.setIncludeFontPadding(false);
            tv.setPadding(dp(8), dp(4), dp(8), dp(4));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC000000);
            bg.setCornerRadius(dp(4));
            tv.setBackground(bg);

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            // ล็อกมุมซ้ายล่าง
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.x = dp(12);
            lp.y = dp(8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            wm.addView(tv, lp);
            showing = true;
        } catch (Exception e) {
            showing = false;
            tv = null;
        }
    }

    public void hide() {
        try {
            if (tv != null && tv.getParent() != null && wm != null) wm.removeView(tv);
        } catch (Exception ignored) {}
        tv = null;
        showing = false;
    }

    public void toggle() {
        if (showing) hide(); else show();
    }

    // เรียกจากเธรดหลักเท่านั้น (RealFpsMonitor ส่งผ่าน handler ของ service อยู่แล้ว)
    public void updateValue(int fps, String source) {
        if (tv == null) return;
        if ("surface".equals(source) && fps >= 0) {
            tv.setText("FPS " + fps);
        } else {
            tv.setText("FPS --");
        }
    }
}
