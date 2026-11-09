package com.mynovo.mynovo1;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Activity เล็กจิ๋ว โปร่งใส ไม่รับโฟกัส/ทัช ใช้เปิดบนจอ DeX (ผ่าน Shizuku: am start --display N)
 * เพื่อให้ระบบยอมให้แอปมองเห็นจอ private นั้น แล้ว FloatingService จึงวาง overlay บนจอ DeX ได้
 * ต้องปล่อยให้ค้างไว้ตลอดที่ FloatingService ทำงาน (FloatingService.onDestroy จะสั่ง finish ให้)
 */
public class DisplayProbeActivity extends Activity {

    public static volatile DisplayProbeActivity instance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = 1;
        lp.height = 1;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.alpha = 0f;
        w.setAttributes(lp);

        setContentView(new View(this));

        // ส่งหน้าต่างเล็กนี้ไปอยู่ด้านหลัง (ย่อ) เพื่อไม่ให้บังเกม; ตัว Activity ยังอยู่ ระบบยังนับว่าแอปเรามีตัวตนบนจอนั้น
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { moveTaskToBack(true); } catch (Exception ignored) {}
        }, 600);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}