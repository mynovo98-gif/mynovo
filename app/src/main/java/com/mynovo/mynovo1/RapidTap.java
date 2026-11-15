package com.mynovo.mynovo1;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/**
 * ระบบ "แตะซ้ำ" (แตะรัว) 1 ปุ่ม = 1 ตัวควบคุม
 * ยิงนิ้วเสมือนลง-ขึ้นซ้ำๆ ที่ตำแหน่งปุ่ม ด้วยความถี่ที่ตั้ง (ครั้งต่อวินาที)
 *
 * ใช้งาน:
 *   RapidTap rt = new RapidTap(PTR_ID);        // PTR_ID ต้องไม่ซ้ำกับนิ้วอื่น
 *   rt.setPosition(x, y);                       // พิกัดกลางปุ่มบนจอจริง
 *   rt.setRate(12f);                            // 12 ครั้ง/วินาที
 *   // กดค้างเพื่อรัว: ตอนกดปุ่มลง -> rt.start()   ตอนปล่อย -> rt.stop()
 *   rt.release();                               // ตอนลบปุ่ม/ปิด service
 */
public class RapidTap {
    public static final float MIN_RATE = 1f;
    public static final float MAX_RATE = 30f;
    private static final long MIN_PRESS_MS = 1L;
    public static final long MIN_INTERVAL_MS = 2L;

    // เธรดแยก ไม่ให้จังหวะแตะสะดุดตามหน้าจอ/main thread
    private static final HandlerThread thread = new HandlerThread("RapidTap");
    private static final Handler handler;
    static {
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private final int pointerId;
    private volatile float x, y;
    private volatile float rate = 10f;
    // [เพิ่ม] ช่วงห่างระหว่างการแตะแต่ละครั้ง (ms) ตั้งตรงๆ ได้ละเอียดถึง 2ms
    private volatile long intervalMs = 100L;
    private volatile long pressMs = 25L;

    private volatile boolean running = false;
    private boolean isDown = false;
    private long token = 0;

    public RapidTap(int pointerId) {
        this.pointerId = pointerId;
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setRate(float tapsPerSecond) {
        this.rate = Math.max(MIN_RATE, Math.min(MAX_RATE, tapsPerSecond));
        this.intervalMs = Math.max(MIN_INTERVAL_MS, Math.round(1000f / this.rate));
    }

    // [เพิ่ม] ตั้งช่วงห่างเป็น ms โดยตรง (เริ่มรอบแตะถัดไปทุกๆ intervalMs)
    public void setIntervalMs(long ms) {
        this.intervalMs = Math.max(MIN_INTERVAL_MS, ms);
    }

    // เวลานิ้วค้างต่อครั้ง (ms) ถ้าเกมไม่รับแตะ ให้เพิ่ม (เช่น 35-50)
    public void setPressMs(long ms) {
        this.pressMs = Math.max(MIN_PRESS_MS, ms);
    }

    public boolean isRunning() {
        return running;
    }

    public void toggle() {
        if (running) stop(); else start();
    }

    public void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(downRunnable);
        handler.removeCallbacks(upRunnable);
        handler.post(downRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(downRunnable);
        handler.removeCallbacks(upRunnable);
        // ปล่อยนิ้วที่ค้างอยู่ทันที (แทรกหน้าคิว ทำในเธรดเดียวกัน กันชนกับรอบแตะที่กำลังทำงาน)
        handler.postAtFrontOfQueue(this::releaseIfDown);
    }

    public void release() {
        stop();
    }

    private void releaseIfDown() {
        if (isDown) {
            TouchSimulator.simulateUp(token, pointerId, Math.round(x), Math.round(y));
            isDown = false;
            token = 0;
        }
    }

    private volatile long nextDownAt = 0;

    private final Runnable downRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long start = SystemClock.uptimeMillis();
            token = TouchSimulator.simulateDown(pointerId, Math.round(x), Math.round(y));
            isDown = token != 0;

            long interval = intervalMs;
            long press = Math.min(pressMs, Math.max(MIN_PRESS_MS, interval - 1L));
            handler.postAtTime(upRunnable, start + press);
            // เก็บเวลาเริ่มรอบไว้คำนวณรอบถัดไปให้จังหวะนิ่ง ไม่เพี้ยนสะสม
            nextDownAt = start + Math.max(press + 1L, interval);
        }
    };

    private final Runnable upRunnable = new Runnable() {
        @Override
        public void run() {
            releaseIfDown();
            if (!running) return;
            handler.postAtTime(downRunnable, nextDownAt);
        }
    };
}
