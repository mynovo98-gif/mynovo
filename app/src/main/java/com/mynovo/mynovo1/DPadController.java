package com.mynovo.mynovo1;

import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;

/**
 * ควบคุม D-pad (W/A/S/D) โดยแปลงเป็นการแตะ+ลากบนจอผ่าน TouchSimulator
 * ใช้ pointer logical id = 1 (ปุ่มแตะธรรมดาใช้ 0)
 */
public class DPadController {

    /** ตัวเชื่อมกลับไปหา FloatingService (กันต้องใช้ inner class) */
    public interface Host {
        int getDpadSize();
        boolean isDestroyed();
    }

    private static final int POINTER_ID = 1;

    private final View containerView;
    private final Handler handler;
    private final Host host;

    private boolean isW = false, isA = false, isS = false, isD = false;
    private boolean isTouching = false;
    private long dpadToken = 0;
    private int lastTx = 0, lastTy = 0;
    // กัน postDelayed ของการกดรอบเก่ามาแทรกรอบใหม่
    private int generation = 0;

    public DPadController(View containerView, Handler handler, Host host) {
        this.containerView = containerView;
        this.handler = handler;
        this.host = host;
    }

    private void setActiveVisual(boolean active) {
        try {
            View img = containerView.findViewWithTag("DPAD_IMG");
            if (img instanceof ImageView) {
                if (active) {
                    ((ImageView) img).setColorFilter(0x66FFFF00, android.graphics.PorterDuff.Mode.SRC_ATOP);
                } else {
                    ((ImageView) img).clearColorFilter();
                }
            }
        } catch (Exception ignored) {}
    }

    private int[] getCenterAndRadius() {
        int[] loc = new int[2];
        containerView.getLocationOnScreen(loc);
        int centerX = loc[0] + (containerView.getWidth() / 2);
        int centerY = loc[1] + (containerView.getHeight() / 2);
        int radius = (int) (host.getDpadSize() * 0.38f);
        return new int[]{centerX, centerY, radius};
    }

    public void release() {
        generation++; // ยกเลิก MOVE ที่ค้างตั้งเวลาไว้
        if (isTouching) {
            int[] cr = getCenterAndRadius();
            if (dpadToken != 0) {
                TouchSimulator.simulateUp(dpadToken, POINTER_ID, cr[0], cr[1]);
                dpadToken = 0;
            }
            isTouching = false;
        }
        isW = isA = isS = isD = false;
        setActiveVisual(false);
    }

    public void onKeyEvent(int keyCode, boolean isDown) {
        if (keyCode == KeyEvent.KEYCODE_W) isW = isDown;
        else if (keyCode == KeyEvent.KEYCODE_A) isA = isDown;
        else if (keyCode == KeyEvent.KEYCODE_S) isS = isDown;
        else if (keyCode == KeyEvent.KEYCODE_D) isD = isDown;

        boolean active = (isW || isA || isS || isD);
        setActiveVisual(active);
        Log.d("DPAD_DBG", "onKeyEvent key=" + keyCode + " down=" + isDown + " active=" + active + " touching=" + isTouching);

        int[] cr = getCenterAndRadius();
        final int cx = cr[0], cy = cr[1], r = cr[2];

        if (active) {
            int dx = 0, dy = 0;
            if (isW) dy -= 1;
            if (isS) dy += 1;
            if (isA) dx -= 1;
            if (isD) dx += 1;

            int targetX = cx;
            int targetY = cy;
            if (dx != 0 || dy != 0) {
                double length = Math.sqrt(dx * dx + dy * dy);
                targetX = cx + (int) ((dx / length) * r);
                targetY = cy + (int) ((dy / length) * r);
            }

            lastTx = targetX;
            lastTy = targetY;

            if (!isTouching || dpadToken == 0 || !TouchSimulator.isPointerDown(POINTER_ID)) {
                final int gen = ++generation;
                // แตะลงที่กลางจอยก่อน แล้วค่อยลากไปขอบ
                dpadToken = TouchSimulator.simulateDown(POINTER_ID, cx, cy);
                isTouching = dpadToken != 0;
                if (isTouching) {
                    for (int i = 1; i <= 4; i++) {
                        final float t = i / 4f;
                        final long delayMs = 50L + 25L * i;
                        handler.postDelayed(() -> {
                            if (host.isDestroyed() || gen != generation) return;
                            if (isTouching && TouchSimulator.isPointerDown(POINTER_ID)) {
                                // ใช้ lastTx/lastTy ล่าสุด เผื่อเปลี่ยนทิศระหว่างรอ
                                TouchSimulator.simulateMove(dpadToken, POINTER_ID,
                                        (int) (cx + (lastTx - cx) * t),
                                        (int) (cy + (lastTy - cy) * t));
                            }
                        }, delayMs);
                    }
                }
            } else {
                TouchSimulator.simulateMove(dpadToken, POINTER_ID, targetX, targetY);
            }
        } else {
            generation++; // ปล่อยปุ่มแล้ว ยกเลิก MOVE ที่ค้าง
            if (isTouching) {
                if (dpadToken != 0) {
                    TouchSimulator.simulateUp(dpadToken, POINTER_ID, cx, cy);
                    dpadToken = 0;
                }
                isTouching = false;
            }
        }
    }
}
