package com.mynovo.mynovo1;

import android.os.Handler;
import android.os.SystemClock;
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

    /**
     * ระยะลากจากกึ่งกลาง เป็นสัดส่วนของขนาด D-pad
     * ยิ่งน้อยยิ่งลากสั้น (เดิม 0.38) — ถ้าเกมไม่ยอมวิ่งเต็มสปีดให้ปรับขึ้น
     */
    private static final float RADIUS_RATIO = 0.38f;

    /** ระยะลากขั้นต่ำ (px) กันเล็กจนเกมมองว่าไม่ได้ขยับ (deadzone) */
    private static final int MIN_RADIUS_PX = 14;

    /** ความไวตอนลากจากกลางไปขอบ: ยิ่งสเต็ปน้อย/หน่วงน้อย ยิ่งไว (เดิม 4 สเต็ป, 50+25*i ms) */
    private static final int RAMP_STEPS = 1;
    private static final long RAMP_START_MS = 5L;
    private static final long RAMP_STEP_MS = 10L;

    /**
     * เวลาถือนิ้วขั้นต่ำก่อนปล่อย — กันกดสั้น ๆ แล้วปล่อยก่อนลากถึงขอบ
     * (ถ้ายังไม่ถึงขอบ จะกระโดดไปขอบทันทีแล้วค้างไว้จนครบเวลานี้)
     */
    private static final long MIN_HOLD_MS = RAMP_START_MS + RAMP_STEP_MS * RAMP_STEPS + 25L;

    private final View containerView;
    private final Handler handler;
    private final Host host;

    private boolean isW = false, isA = false, isS = false, isD = false;
    private boolean isTouching = false;
    private long dpadToken = 0;
    private int lastTx = 0, lastTy = 0;
    // กัน postDelayed ของการกดรอบเก่ามาแทรกรอบใหม่
    private int generation = 0;
    private long downTime = 0;

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
        // คิดกึ่งกลางจากรูป D-pad จริง (ถ้าหาไม่เจอหรือยังไม่ได้วัดขนาด ค่อยใช้ container แทน)
        View target = containerView;
        try {
            View img = containerView.findViewWithTag("DPAD_IMG");
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                target = img;
            }
        } catch (Exception ignored) {}

        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        int centerX = loc[0] + (target.getWidth() / 2);
        int centerY = loc[1] + (target.getHeight() / 2);
        int radius = Math.max(MIN_RADIUS_PX, (int) (host.getDpadSize() * RADIUS_RATIO));
        return new int[]{centerX, centerY, radius};
    }

    /** ปล่อยนิ้วที่ตำแหน่งล่าสุดที่ลากไป (ถ้าไม่มีให้ใช้กึ่งกลาง) */
    private void doUp(int cx, int cy) {
        if (isTouching && dpadToken != 0) {
            int ux = (lastTx != 0 || lastTy != 0) ? lastTx : cx;
            int uy = (lastTx != 0 || lastTy != 0) ? lastTy : cy;
            TouchSimulator.simulateUp(dpadToken, POINTER_ID, ux, uy);
        }
        dpadToken = 0;
        isTouching = false;
        lastTx = 0;
        lastTy = 0;
    }

    public void release() {
        generation++; // ยกเลิก MOVE ที่ค้างตั้งเวลาไว้
        if (isTouching) {
            int[] cr = getCenterAndRadius();
            doUp(cr[0], cr[1]);
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
                downTime = SystemClock.uptimeMillis();
                if (isTouching) {
                    for (int i = 1; i <= RAMP_STEPS; i++) {
                        final float t = i / (float) RAMP_STEPS;
                        final long delayMs = RAMP_START_MS + RAMP_STEP_MS * i;
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
                generation++; // ยกเลิก MOVE/UP ที่ค้างจากรอบก่อน (กรณีกดซ้ำเร็ว ๆ)
                TouchSimulator.simulateMove(dpadToken, POINTER_ID, targetX, targetY);
            }
        } else {
            generation++; // ปล่อยปุ่มแล้ว ยกเลิก MOVE ที่ค้าง
            if (isTouching) {
                long elapsed = SystemClock.uptimeMillis() - downTime;
                if (elapsed < MIN_HOLD_MS && dpadToken != 0
                        && (lastTx != 0 || lastTy != 0)) {
                    // ยังลากไม่ถึงขอบ: กระโดดไปขอบทันที แล้วค้างไว้ให้เกมเห็นก่อนปล่อย
                    TouchSimulator.simulateMove(dpadToken, POINTER_ID, lastTx, lastTy);
                    final int gen = generation;
                    handler.postDelayed(() -> {
                        if (host.isDestroyed() || gen != generation) return;
                        doUp(cx, cy);
                    }, MIN_HOLD_MS - elapsed);
                } else {
                    doUp(cx, cy);
                }
            }
        }
    }
}
