package com.mynovo.mynovo1;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

public class KeyBlockService extends AccessibilityService {

    private String lastPkg = "";
    private long lastAutoStart = 0;

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
        if (s != null) s.handleKey(event);
        return true;
    }

    private Set<String> savedGamePackages() {
        Set<String> set = new HashSet<>();
        try {
            String js = getSharedPreferences("MynovoPrefs", MODE_PRIVATE)
                    .getString("savedGamesJson", "");
            if (!js.isEmpty()) {
                JSONArray a = new JSONArray(js);
                for (int i = 0; i < a.length(); i++)
                    set.add(a.getJSONObject(i).getString("packageName"));
            }
        } catch (Exception ignored) {}
        return set;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();

        // ข้ามหน้าต่างระบบ/คีย์บอร์ด/overlay ของแอปเรา ไม่งั้นจะนับเป็นการออกจากเกม
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod") || pkg.contains("keyboard")) return;

        if (pkg.equals(lastPkg)) return;
        lastPkg = pkg;

        if (FloatingService.instance != null) return;           // ทำงานอยู่แล้ว
        long now = SystemClock.uptimeMillis();
        if (now - lastAutoStart < 3000) return;                 // กันสตาร์ทรัว
        if (!savedGamePackages().contains(pkg)) return;
        if (!Settings.canDrawOverlays(this)) return;

        lastAutoStart = now;
        try {
            Intent i = new Intent(this, FloatingService.class);
            i.putExtra("GAME_PACKAGE", pkg);
            // ไม่ใส่ GAME_DISPLAY: service ตรวจเองว่าเกมอยู่จอไหน (มือถือ/DeX)
            startForegroundService(i);
        } catch (Exception ex) {
            Log.w("KeyBlock", "auto start failed: " + ex);
        }
    }

    @Override public void onInterrupt() {}
}
