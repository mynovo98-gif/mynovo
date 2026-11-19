package com.mynovo.mynovo1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * แสดงตัวเลข FPS ลอยนิ่งมุมซ้ายบนของจอ แบบเดียวกับที่ BlueStacks แสดง
 * ตำแหน่งตายตัว - ไม่มี touch listener ให้ลากย้ายได้
 */
public class FpsOverlay {

    private final Context context;
    private final WindowManager windowManager;

    private TextView fpsText;
    private WindowManager.LayoutParams params;

    public FpsOverlay(Context context, WindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;
    }

    public boolean isShowing() {
        return fpsText != null;
    }

    public void show() {
        if (fpsText != null) return;
        try {
            fpsText = new TextView(context);
            fpsText.setText(".. FPS");
            fpsText.setTextColor(Color.parseColor("#00E676"));
            fpsText.setTextSize(11f);
            fpsText.setTypeface(null, Typeface.BOLD);
            fpsText.setPadding(dp(8), dp(3), dp(8), dp(3));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#99000000"));
            bg.setCornerRadius(dp(8));
            fpsText.setBackground(bg);

            // ไม่รับการแตะใดๆ ทั้งสิ้น ไม่สามารถลากย้ายตำแหน่งได้
            fpsText.setClickable(false);
            fpsText.setFocusable(false);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE   // แตะทะลุผ่านได้ ไม่บังคลิก และลากย้ายไม่ได้
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            // ตำแหน่งตายตัวมุมซ้ายบน (เหมือน BlueStacks) - ไม่มีช่องให้ตั้งค่าตำแหน่งอื่น
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = dp(8);
            params.y = dp(8);

            windowManager.addView(fpsText, params);
        } catch (Exception ignored) {}
    }

    public void hide() {
        if (fpsText != null) {
            try { windowManager.removeView(fpsText); } catch (Exception ignored) {}
            fpsText = null;
        }
    }

    public void toggle() {
        if (isShowing()) hide(); else show();
    }

    /**
     * อัปเดตค่า FPS ที่แสดง
     * source: "surface" = วัดจริงจาก SurfaceFlinger (แม่นสุด)
     *         "gfxinfo"  = fallback จากตัวนับสะสมของแอป
     *         "vsync"/"none" = ประมาณการ/วัดไม่ได้
     */
    public void updateValue(int fps, String source) {
        if (fpsText == null) return;
        if ("none".equals(source) || fps < 0) {
            fpsText.setText("N/A");
            fpsText.setTextColor(Color.parseColor("#FF5555"));
            return;
        }
        String tag = "surface".equals(source) ? "" : "gfxinfo".equals(source) ? " ~" : " ≈";
        fpsText.setText(fps + tag + " FPS");
        fpsText.setTextColor(Color.parseColor(
                fps >= 50 ? "#00E676" : fps >= 30 ? "#FFEB3B" : "#FF5555"));
    }

    private int dp(int v) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(v * density);
    }
}
