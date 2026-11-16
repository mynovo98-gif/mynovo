package com.mynovo.mynovo1;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class KeyBlockService extends AccessibilityService {

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!FloatingService.blockMetaKey) return false;

        int kc = event.getKeyCode();
        int sc = event.getScanCode();

        boolean meta = kc == KeyEvent.KEYCODE_META_LEFT
                || kc == KeyEvent.KEYCODE_META_RIGHT
                || sc == 125 || sc == 126;
        boolean caps = kc == KeyEvent.KEYCODE_CAPS_LOCK || sc == 58;

        if (!meta && !caps) return false;

        FloatingService s = FloatingService.instance;
        if (s != null) s.handleKey(event); // ส่งต่อให้ระบบแมปปุ่มของแอป
        return true; // กลืนปุ่ม ไม่ให้ระบบเอาไปใช้
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
