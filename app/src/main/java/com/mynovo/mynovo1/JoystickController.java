package com.mynovo.mynovo1;

import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

/**
 * ควบคุมจอยสติ๊กเสมือน (ลากนิ้วรอบทิศทาง 360 องศา) แล้วแปลงเป็นการแตะ+ลากนอกจอผ่าน TouchSimulator
 * ใช้ pointer logical id = 3 (ปุ่มแตะธรรมดาใช้ 0, D-pad ใช้ 1, เมาส์ใช้ 2)
 *
 * พฤติกรรม (ค่าพื้นฐานที่ตั้งไว้ ปรับได้ถ้าของเดิมไม่เหมือนนี้):
 * - ลากได้รอบทิศทาง 360 องศา ไม่จำกัดมุม
 * - ลากเกินรัศมีวงกลม -> หัวจอยค้างอยู่ที่ขอบ ไม่ไหลตามนิ้วออกไปอีก
 * - ปล่อยนิ้ว -> หัวจอยเด้งกลับจุดกึ่งกลางเอง และยกนิ้วเสมือนออกจากเกม
 */
public class JoystickController {

    private static final String TAG = "JoystickController";

    /** ตัวเชื่อมกลับไปหา FloatingService (กันต้องใช้ inner class) */
    public interface Host {
        int getJoystickSize();
        boolean isDestroyed();
    }

    private static final int POINTER_ID = 3;

    private final View containerView;   // พื้นที่ฐานวงกลมจอย (รับ touch event)
    private final ImageView knobView;   // หัวจอยที่ขยับตามนิ้ว (อาจเป็น null ถ้าไม่มีภาพแยก)
    private final Handler handler;
    private final Host host;

    // ตำแหน่งเป้าหมายจริงในเกม (จุดศูนย์กลางที่เกมอ่านว่าจอยอยู่ตรงนี้)
    private final int targetCx;
    private final int targetCy;
    private final int targetRadius;

    private boolean isTouching = false;
    private long joystickToken = 0;
    private int generation = 0;

    // ระยะตายกลางจอย กันสั่นตอนนิ้วแทบไม่ขยับ (พิกเซลของ containerView เอง)
    private static final float DEAD_ZONE_PX = 6f;

    public JoystickController(View containerView, ImageView knobView, Handler handler, Host host,
                               int targetCx, int targetCy, int targetRadius) {
        this.containerView = containerView;
        this.knobView = knobView;
        this.handler = handler;
        this.host = host;
        this.targetCx = targetCx;
        this.targetCy = targetCy;
        this.targetRadius = targetRadius;

        containerView.setOnTouchListener(this::onTouch);
    }

    private boolean onTouch(View v, MotionEvent event) {
        if (host.isDestroyed()) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleMove(event.getX(), event.getY(), true);
                return true;

            case MotionEvent.ACTION_MOVE:
                handleMove(event.getX(), event.getY(), false);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return false;
    }

    private void handleMove(float touchX, float touchY, boolean isDown) {
        float baseCx = containerView.getWidth() / 2f;
        float baseCy = containerView.getHeight() / 2f;
        float maxDrag = Math.min(baseCx, baseCy); // รัศมีลากสูงสุดบนฐานจอยสติ๊ก (พิกเซลของ containerView)

        float dx = touchX - baseCx;
        float dy = touchY - baseCy;
        float dist = (float) Math.hypot(dx, dy);

        // กันสั่นช่วงกึ่งกลาง
        if (dist < DEAD_ZONE_PX) {
            dx = 0f;
            dy = 0f;
            dist = 0f;
        }

        // ลากเกินขอบ -> หยุดที่ขอบ ไม่ไหลตามนิ้วออกไปอีก
        float clampedDx = dx;
        float clampedDy = dy;
        if (dist > maxDrag && dist > 0f) {
            float scale = maxDrag / dist;
            clampedDx = dx * scale;
            clampedDy = dy * scale;
        }

        // ขยับภาพหัวจอยให้ตรงกับนิ้ว (ถ้ามี view แยก)
        if (knobView != null) {
            knobView.setTranslationX(clampedDx);
            knobView.setTranslationY(clampedDy);
        }

        // แปลงเป็นพิกัดเป้าหมายจริงในเกม ตามสัดส่วนรัศมีเป้าหมาย / รัศมีฐานจอยบนจอ
        float ratio = (maxDrag > 0f) ? (targetRadius / maxDrag) : 1f;
        int finalX = targetCx + Math.round(clampedDx * ratio);
        int finalY = targetCy + Math.round(clampedDy * ratio);

        final int gen = ++generation;

        if (isDown || joystickToken == 0 || !isTouching) {
            isTouching = true;
            joystickToken = TouchSimulator.simulateDown(POINTER_ID, targetCx, targetCy);
            // DOWN ที่จุดศูนย์กลางก่อน แล้วค่อยลากไปตำแหน่งจริงเฟรมถัดไป กันเกมตีความ DOWN ผิดตำแหน่ง
            handler.postDelayed(() -> {
                if (host.isDestroyed() || gen != generation) return;
                if (isTouching && TouchSimulator.isPointerDown(POINTER_ID)) {
                    TouchSimulator.simulateMove(joystickToken, POINTER_ID, finalX, finalY);
                }
            }, 16L);
        } else {
            TouchSimulator.simulateMove(joystickToken, POINTER_ID, finalX, finalY);
        }
    }

    /** ปล่อยนิ้ว -> หัวจอยเด้งกลับกึ่งกลาง และยกนิ้วเสมือนออกจากเกม */
    private void release() {
        generation++; // ยกเลิก postDelayed ที่ค้างอยู่
        if (knobView != null) {
            knobView.setTranslationX(0f);
            knobView.setTranslationY(0f);
        }
        if (isTouching) {
            if (joystickToken != 0) {
                TouchSimulator.simulateUp(joystickToken, POINTER_ID, targetCx, targetCy);
                joystickToken = 0;
            }
            isTouching = false;
        }
    }

    /** เรียกตอน service ถูกทำลาย/หยุดใช้งานจอย กันนิ้วเสมือนค้าง */
    public void releaseImmediately() {
        try {
            release();
        } catch (Exception e) {
            Log.w(TAG, "releaseImmediately failed", e);
        }
    }
}
