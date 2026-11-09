package com.mynovo.mynovo1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class MouseController {
    // ความทึบของหน้าต่างล็อคเมาส์ (รับ input การขยับ/คลิก) - ถ้าเพิ่มแล้ว D-pad/ปุ่มแตะกลับมากดไม่ลง ให้ลดลง
    private static final float MOUSE_LOCK_ALPHA = 0.05f;
    // ความทึบของหน้าต่างเคอร์เซอร์ (ชั้นที่วาดไอคอนเมาส์) - ปรับแยกจากหน้าต่างล็อคเมาส์ได้อิสระ
    private static final float CURSOR_LAYER_ALPHA = 0.40f;

    // true  = นิ้วเสมือนลากเลยขอบจอได้ (พิกัดติดลบ/เกินจอ) ไม่ต้องยกนิ้ว กล้องหมุนลื่น
    // false = จำกัดในจอ แล้วยกนิ้ววางใหม่เมื่อถึงระยะ (กล้องจะกระตุกตอนรีเซ็ต)
    // ถ้าเปิดแล้วกล้องไม่ขยับ/หยุดตอนเลยขอบ ให้ตั้งเป็น false
    private static final boolean VIEW_ALLOW_OFFSCREEN = true;

    // ลอยได้ไกลสุดจากจุดเริ่มกี่เท่าของจอ ก่อนรีเซ็ต (เดิม 50 เท่า) ลดแล้วชดเชยแม่นขึ้น
    // ถ้ากล้องกระตุกบ่อยให้เพิ่ม (เช่น 2.5), ถ้ายังช้าเร็วไม่คงที่ให้ลด (เช่น 1.0)
    private static final float VIEW_LIMIT_SCREENS = 50f;

    // true = โหมดความเร็วคงที่: ความเร็ว = ระยะเมาส์ x ค่าความไว ล้วนๆ ไม่มีตัวคูณตามตำแหน่งเลย (ทั้ง X และ Y)
    //        นิ้วเสมือนอยู่ในโซนฝั่งขวา (ที่เกมให้ความเร็วเท่ากัน) ไม่ออกนอกจอ
    //        ถ้าออกนอกโซนจะวางนิ้วใหม่ที่ปลายด้านตรงข้ามทิศที่หมุน (ได้ระยะลากไกลสุด)
    //        และรีเซ็ตกลับจุดเริ่มเงียบๆ ตอนเมาส์หยุดขยับ
    // false = พฤติกรรมเดิม (ตัวชดเชยตามตำแหน่ง)
    private static final boolean VIEW_CONSTANT_ZONE = false;
    // ขอบเขตโซน (สัดส่วนของจอ) ซ้ายต้องมากกว่าเส้นแบ่งซ้าย/ขวา (LEFT_HALF_X + LEFT_HALF_BAND = ~0.575)
    // อยากลากได้ไกลขึ้นก่อนรีเซ็ต ให้ขยายโซน (ถ้าความเร็วเริ่มเพี้ยนที่ขอบ ให้หดกลับ)
    private static final float ZONE_X_MIN = 0.60f;
    private static final float ZONE_X_MAX = 0.97f;
    private static final float ZONE_Y_MIN = 0.12f;
    private static final float ZONE_Y_MAX = 0.88f;
    // วางนิ้วใหม่ที่ตำแหน่งกี่ % ของโซนจากปลายด้านตรงข้าม (0.1 = เหลือที่ให้ลากเกือบเต็มโซน)
    private static final float ZONE_RESTART = 0.10f;
    // เมาส์หยุดขยับกี่ ms ถึงรีเซ็ตนิ้วกลับจุดเริ่ม (กล้องนิ่งอยู่ จึงไม่เห็นกระตุก)
    private static final long VIEW_IDLE_RECENTER_MS = 150L;

    // [เพิ่ม] ช่วงปลอดภัยของ Y ตอน "วางนิ้วใหม่" (สัดส่วนของความสูงจอ)
    // DOWN ใหม่ห้ามอยู่ติดขอบบน/ล่าง ไม่งั้นระบบมองเป็นการปัดจากขอบ (ดึงแถบแจ้งเตือน/ท่าทางโฮม)
    // นิ้วที่ลากเลยขอบหลังวางแล้วยังทะลุได้เหมือนเดิม (ไม่เกี่ยวกับค่านี้)
    private static final float DOWN_SAFE_Y_MIN = 0.12f;
    private static final float DOWN_SAFE_Y_MAX = 0.88f;

    // ระยะลากสูงสุดของโหมดมุมมองจากจุดเริ่ม (สัดส่วนของความกว้าง/สูงจอ) เกินแล้วจะยกนิ้วแล้ววางใหม่
    // เพิ่มค่า = ลากได้ไกลขึ้นก่อนรีเซ็ต, ลดค่า = รีเซ็ตถี่ขึ้น
    private static final float VIEW_RANGE_X = 0.10f;
    private static final float VIEW_RANGE_Y = 0.12f;

    // เกมให้ความเร็วกล้องครึ่งจอซ้ายเร็วกว่าครึ่งขวา เลยชดเชยให้เท่ากัน (ไม่กั้นนิ้ว)
    // LEFT_HALF_GAIN: 1.0 = ปิดการชดเชย (ความเร็วคงที่ทั้งจอ) / ถ้าซ้ายเร็วกว่าขวาให้ลดลง (เช่น 0.5)
    // หมายเหตุ: ตอนนี้ factorX() ไม่ได้ใช้ตัวคูณซีกซ้ายแล้ว (ตัดออกเพราะทำให้แกน X หน่วง)
    private static final float LEFT_HALF_X = 0.525f;
    private static final float LEFT_HALF_GAIN = 0.15f;
    private static final float LEFT_HALF_BAND = 0.15f;

    // ตอนนิ้วเสมือนลากหลุดขอบจอ เกมให้ความเร็วเร็วขึ้น เลยชดเชยให้เท่าตอนอยู่ในจอ (ใช้ทั้งแกน X และ Y)
    // OFFSCREEN_GAIN: 1.0 = ปิดการชดเชย (ความเร็วคงที่ทั้งแกน X และ Y)
    // ถ้านอกจอยังเร็วกว่าในจอให้ลดลงเล็กน้อย (เช่น 0.8)
    private static final float OFFSCREEN_GAIN_X = 0.15f;
    // ตัวคูณต่ำสุดของแกน X นอกจอ (กันช้าลงเรื่อยๆ ตอนสะบัดไกล) เพิ่ม = นอกจอเร็วขึ้น
    private static final float OFFSCREEN_MIN_X = 0.15f;
    // แกน Y กลับไปใช้ค่าเดิม (1.0 = ไม่ชดเชย)
    private static final float OFFSCREEN_GAIN_Y = 1.0f;
    // ระยะที่ค่อยๆ เปลี่ยนความเร็วเมื่อเลยขอบออกไป (สัดส่วนของขนาดจอ) จะได้ไม่กระโดดตรงขอบ
    // ขยายจาก 0.005 เป็น 0.03 ให้ความเร็วเปลี่ยนนุ่มขึ้นตรงขอบจอ นิ้วที่แกว่งอยู่รอบขอบจะไม่สั่น
    private static final float OFFSCREEN_BAND = 0.30f;
    // BAND ของแกน Y (ค่าเดิม)
    private static final float OFFSCREEN_BAND_Y = 0.04f;
    private static final boolean OFFSCREEN_STACK_LEFT = false;

    // ตัวคูณความเร็วตามระยะที่นิ้วเสมือนเลยขอบจอ แกน X (ลดต่อเนื่อง ไม่มีเพดาน)
    // (v = พิกัดบนแกนนั้น, max = ขนาดจอแกนนั้น)
    private float offscreenFactor(float v, float max, float gainOut) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f) return 1f;
        float t = d / Math.max(1f, OFFSCREEN_BAND * max);
        float g = Math.max(0.05f, gainOut);
        float f = 1f / (1f + (1f / g - 1f) * t);
        return Math.max(OFFSCREEN_MIN_X, f);
    }

    // ตัวคูณแกน Y: สูตรเดิม ลดถึง gainOut แล้วหยุด
    private float offscreenFactorY(float v, float max, float gainOut) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f) return 1f;
        float t = Math.min(1f, d / Math.max(1f, OFFSCREEN_BAND_Y * max));
        return 1f - (1f - gainOut) * t;
    }

    // ตัวคูณความเร็วแกน X ตามตำแหน่งนิ้วเสมือน เปลี่ยนค่อยๆ รอบเส้นแบ่ง ไม่กระโดด
    private float leftHalfFactor(float x) {
        float band = Math.max(1f, LEFT_HALF_BAND * maxScreenWidth);
        float t = (x - (LEFT_HALF_X * maxScreenWidth - band)) / (2f * band);
        t = Math.max(0f, Math.min(1f, t));
        return LEFT_HALF_GAIN + (1f - LEFT_HALF_GAIN) * t;
    }

    // ตัวคูณแกน X: ใช้เฉพาะตัวชดเชยตอนหลุดขอบจอ (ตัดตัวคูณซีกซ้ายออก ความเร็วในจอคงที่)
    // นิ้วจะถูกพากลับจุดเริ่มตอนเมาส์หยุดขยับ (idleRecenterRunnable) ไม่ลอยสะสมไกล
    private float factorX(float x) {
        return offscreenFactor(x, maxScreenWidth, OFFSCREEN_GAIN_X);
    }

    private final Context context;
    private final WindowManager windowManager;

    private FrameLayout mouseLockView;
    private WindowManager.LayoutParams mouseLockParams;
    private ImageView customCursorView;

    // หน้าต่างแยกสำหรับเคอร์เซอร์ จะได้ยกขึ้นมาอยู่บนสุดได้ (ไม่ไปแอบหลังเมนู/หน้าตั้งค่า)
    private FrameLayout cursorLayer;
    private WindowManager.LayoutParams cursorLayerParams;

    private boolean isMouseMode = false;
    private boolean isPaused = false;
    private boolean destroyed = false;

    // โหมดพิมพ์: overlay ไม่รับโฟกัส/ไม่จับ pointer capture เพื่อให้เกมได้รับแป้นตรงๆ
    private boolean typingMode = false;

    private float cursorX = 0f;
    private float cursorY = 0f;
    private float maxScreenWidth;
    private float maxScreenHeight;

    private float mouseSensitivity;
    private int cursorSize;

    private long mouseMoveToken = 0;
    private boolean pressing = false;
    // คลิกขวากำลังถูกกดอยู่ (กัน onRightClick ถูกเรียกซ้ำจาก DOWN + BUTTON_PRESS ของการคลิกครั้งเดียว)
    private boolean rightDown = false;

    // [เพิ่ม] นิ้วหลอกตัวที่ 3 แปะนิ่งตลอดช่วงล็อคเมาส์ (โหมดมุมมอง)
    // เหตุผล: บางเกมตีความ "2 นิ้วพร้อมกัน (ยิง + ลากมุมมอง) โดยนิ้วหนึ่งนิ่ง อีกนิ้วลาก"
    // เป็นท่า pinch/rotate แทนที่จะมองกล้องปกติ ปล่อย MOVE ไม่ขึ้น ถ้ามีนิ้วที่ 3 (เช่นเดินด้วย)
    // เกมจะหลุดเงื่อนไข 2-finger gesture แล้วอ่านนิ้วแยกกันตามปกติ กล้องเลยขึ้น
    // เราเลยแปะนิ้วหลอกตัวที่ 3 ไว้นิ่งๆ ตลอดเวลาที่ล็อคเมาส์ ให้มีนิ้วครบ 3 เสมอไม่ว่าจะเดินอยู่หรือไม่
    private static final int ANCHOR_POINTER_ID = 4;
    private long anchorToken = 0;

    // เก็บเป็น double กันค่าสะสมถูกปัดทิ้งเมื่อนิ้วเสมือนลอยไกลมาก (float ละเอียดลดลงตามขนาดตัวเลข)
    private double viewX = 0.0;
    private double viewY = 0.0;

    // มี inject ที่นัดส่งไว้ (trailing) รออยู่หรือไม่
    private boolean injectScheduled = false;

    // ความถี่ส่งตำแหน่งนิ้วเสมือนของโหมดมุมมอง (Hz) ตั้งจากหน้าตั้งค่าปุ่มล็อกเมาส์
    // 0 = ส่งทันทีทุก event (ไม่รอเฟรมจอ ไม่รวม event), มากกว่า 0 = จำกัดความถี่ตามที่ตั้ง (ช่วง 30 - 1000)
    private int viewInjectHz = 0;
    private long lastInjectUptime = 0;
    private final android.os.Handler injectHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable injectRunnable = this::doViewInject;

    // ยก-วางนิ้วเสมือนใหม่ที่พิกัด (nx, ny) UP ที่ตำแหน่งเดิม (บีบให้อยู่ในจอ) แล้ว DOWN ที่ตำแหน่งใหม่
    // [แก้ไข] DOWN ใหม่ต้องไม่อยู่ติดขอบบน/ล่าง ไม่งั้นระบบมองเป็นการปัดจากขอบ (ดึงแถบแจ้งเตือน/ท่าทางโฮม)
    // ตอนนี้นิ้วเก่ายกขึ้นแล้วและนิ้วใหม่ยังไม่ขยับ กล้องจึงไม่กระตุกจากการบีบ Y
    private void recenterTo(float nx, float ny) {
        if (mouseMoveToken != 0) {
            float ux = (float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX));
            float uy = (float) Math.max(0.0, Math.min(maxScreenHeight - 1, viewY));
            TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round(ux), Math.round(uy));
        }
        final float safeTop = maxScreenHeight * DOWN_SAFE_Y_MIN;
        final float safeBottom = maxScreenHeight * DOWN_SAFE_Y_MAX;
        ny = Math.max(safeTop, Math.min(safeBottom, ny));
        viewX = nx;
        viewY = ny;
        mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
    }

    // เมาส์หยุดขยับ -> พานิ้วเสมือนกลับจุดเริ่มเฉพาะแกน X (Y คงเดิม) เพื่อให้ครั้งหน้ามีระยะลากเต็ม
    private final Runnable idleRecenterRunnable = new Runnable() {
        @Override
        public void run() {
            // ห้ามรีเซ็ตตอนกดยิง/กดคลิกค้างอยู่ (ตอนยิงแล้วหยุดเมาส์แป๊บเดียวแล้วลากต่อ จะเกิดอาการล็อค)
            if (destroyed || isPaused || !isMouseMode || mouseMoveToken == 0 || pressing) return;
            float sx = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
            if (Math.abs(viewX - sx) > maxScreenWidth * 0.02f) {
                recenterTo(sx, (float) viewY);
            }
        }
    };

    // ขอ pointer capture ให้ติดเร็วตอนสลับโหมด: ขอทันที ถ้ายังไม่ติดลองซ้ำทุก 50ms (สูงสุด ~400ms)
    // แทนการรอ loop ตรวจโฟกัสทุก 400ms
    private int captureTries = 0;
    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || isPaused || typingMode || mouseLockView == null) return;
            boolean ok;
            try {
                mouseLockView.requestFocus();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!mouseLockView.hasPointerCapture()) mouseLockView.requestPointerCapture();
                    ok = mouseLockView.hasPointerCapture();
                } else {
                    ok = mouseLockView.isFocused();
                }
            } catch (Exception e) {
                return;
            }
            if (!ok && ++captureTries < 20) injectHandler.postDelayed(this, 16);
        }
    };

    private void requestCaptureFast() {
        injectHandler.removeCallbacks(captureRunnable);
        captureTries = 0;
        captureRunnable.run();
    }

    // นับจำนวนรอบที่โฟกัสหลุด ถ้าหลุดต่อเนื่องจะบังคับให้ window ขอโฟกัสใหม่
    private int focusMissCount = 0;
    private int forceAttempts = 0;
    private long lastForceTime = 0;

    private float targetBtnX = 0f;
    private float targetBtnY = 0f;
    private boolean hasCustomTarget = false;

    // ความเร็วแยกแกนของโหมดมุมมอง (ตั้งจากหน้าตั้งค่าปุ่มล็อกเมาส์)
    private float viewSensX = 1f;
    private float viewSensY = 1f;

    private final MouseEventListener eventListener;

    public interface MouseEventListener {
        boolean onMouseClickUI(float x, float y, int action);
        void onRightClick();
        boolean onKeyEvent(KeyEvent event);
        // แจ้งเมื่อ window เสียโฟกัส เพื่อปล่อย D-Pad ไม่ให้ค้าง (KEY_UP จะไม่มาถึงอีก)
        void onWindowFocusLost();
        // กดคลิกซ้ายลง ตอนล็อคเมาส์ (โหมดมุมมอง) -> ค้างนิ้วไว้จนกว่าจะปล่อย
        void onMouseLockedLeftDown();
        // ปล่อยคลิกซ้าย ตอนล็อคเมาส์
        void onMouseLockedLeftUp();
    }

    public MouseController(Context context, WindowManager windowManager, float maxWidth, float maxHeight, int cursorSize, float sensitivity, MouseEventListener listener) {
        this.context = context;
        this.windowManager = windowManager;
        this.maxScreenWidth = maxWidth;
        this.maxScreenHeight = maxHeight;
        this.cursorSize = cursorSize;
        this.mouseSensitivity = sensitivity;
        this.eventListener = listener;

        this.targetBtnX = maxWidth * 0.79f;
        this.targetBtnY = maxHeight * 0.55f;
        this.hasCustomTarget = true;

        initView();
    }

    public void setTargetButtonPosition(float x, float y) {
        this.targetBtnX = x;
        this.targetBtnY = y;
        this.hasCustomTarget = true;
    }

    // ความชันของค่าความไว (ตั้งจากหน้าตั้งค่า): 1.0 = เหมือนเดิม (เส้นตรง), น้อยกว่า 1 = ปรับนิดเดียวไม่เปลี่ยนเยอะ
    // ถ้ายังไวเกินให้ลด (0.2) ถ้าปรับแล้วแทบไม่เปลี่ยนให้เพิ่ม (0.5)
    private static final float VIEW_SENS_CURVE = 0.8f;
    // ค่าความไวที่ตอนนี้รู้สึกพอดี แยกแกน (ตรงค่านี้ความเร็วเท่าเดิมเป๊ะ) ใส่ตามที่ตั้งในหน้าตั้งค่า
    private static final float VIEW_SENS_PIVOT_X = 0.04f;
    private static final float VIEW_SENS_PIVOT_Y = 0.02f;

    private float softenViewSens(float s, float pivot) {
        if (s <= 0f) return 0f;
        return (float) (pivot * Math.pow(s / pivot, VIEW_SENS_CURVE));
    }

    public void setViewSensitivity(float sx, float sy) {
        this.viewSensX = softenViewSens(sx, VIEW_SENS_PIVOT_X);
        this.viewSensY = softenViewSens(sy, VIEW_SENS_PIVOT_Y);
    }

    // ตั้งความถี่ส่งตำแหน่งของโหมดมุมมอง (Hz) 0 = ส่งทันทีทุก event
    public void setViewHz(int hz) {
        if (hz <= 0) {
            this.viewInjectHz = 0;
        } else {
            this.viewInjectHz = Math.max(30, Math.min(1000, hz));
        }
    }

    private void initView() {
        mouseLockView = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (eventListener != null && eventListener.onKeyEvent(event)) {
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            // ทวงคืน focus + pointer capture ทันทีที่ window ได้โฟกัสกลับมา
            @Override
            public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (destroyed || isPaused) return;
                if (hasWindowFocus) {
                    if (typingMode) return;
                    forceAttempts = 0;
                    requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requestPointerCapture();
                    }
                } else if (eventListener != null) {
                    eventListener.onWindowFocusLost();
                }
            }
        };
        mouseLockView.setBackgroundColor(Color.parseColor("#01000000"));

        cursorX = maxScreenWidth / 2f;
        cursorY = maxScreenHeight / 2f;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mouseLockView.setFocusable(true);
            mouseLockView.setFocusableInTouchMode(true);

            mouseLockView.setOnCapturedPointerListener((v, e) -> {
                if (!isPaused && !destroyed) handlePointerEvent(e);
                return true;
            });
        }

        mouseLockParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                // ไม่ให้ IME (แป้นพิมพ์ภาษาอื่น) มาดัก/แปลงคีย์ก่อนถึง dispatchKeyEvent
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
        );
        mouseLockParams.gravity = Gravity.TOP | Gravity.START;
        // Android 12+ (API 31) บล็อกทัชที่ทะลุ overlay เมื่อ "ความทึบรวมกัน" เกิน 0.8
        // หน้าต่างนี้เต็มจอและซ้อนกับปุ่มทุกตัว จึงตั้งให้ใสมาก
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mouseLockParams.alpha = MOUSE_LOCK_ALPHA;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mouseLockParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        windowManager.addView(mouseLockView, mouseLockParams);

        cursorLayer = new FrameLayout(context);
        cursorLayerParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        cursorLayerParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cursorLayerParams.alpha = CURSOR_LAYER_ALPHA;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cursorLayerParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        windowManager.addView(cursorLayer, cursorLayerParams);

        mouseLockView.post(() -> {
            if (!isPaused && !destroyed) setMouseMode(false);
        });
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ขนาดจอจริงเปลี่ยนได้ (หมุนจอ / เกมเป็นแนวนอนแต่ service ถูกสร้างตอนแอปอยู่แนวตั้ง)
    @SuppressWarnings("deprecation")
    public void refreshScreenSize() {
        try {
            if (windowManager == null) return;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            float w = dm.widthPixels;
            float h = dm.heightPixels;
            if (w <= 0 || h <= 0) return;
            if (w == maxScreenWidth && h == maxScreenHeight) return;

            float sx = (maxScreenWidth > 0) ? w / maxScreenWidth : 1f;
            float sy = (maxScreenHeight > 0) ? h / maxScreenHeight : 1f;
            maxScreenWidth = w;
            maxScreenHeight = h;

            targetBtnX *= sx; targetBtnY *= sy;
            cursorX = Math.max(0f, Math.min(w - 1, cursorX * sx));
            cursorY = Math.max(0f, Math.min(h - 1, cursorY * sy));
            viewX *= sx; viewY *= sy;

            // ปล่อยนิ้วเมาส์ที่ค้างอยู่ตำแหน่งเก่า ครั้งหน้าที่เมาส์ขยับจะ DOWN ใหม่ในพิกัดที่ถูกต้อง
            if (mouseMoveToken != 0) {
                TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) (viewX / sx)), Math.round((float) (viewY / sy)));
                mouseMoveToken = 0;
            }
            // [เพิ่ม] ปล่อยนิ้วหลอกด้วย เดี๋ยวค้างตำแหน่งเก่า (ครั้งหน้าเข้าโหมดมุมมองใหม่จะ DOWN ให้เอง)
            if (anchorToken != 0) {
                TouchSimulator.simulateUp(anchorToken, ANCHOR_POINTER_ID,
                        Math.round((float) (viewX / sx)), Math.round((float) (viewY / sy)));
                anchorToken = 0;
            }
        } catch (Exception ignored) {}
    }

    public void pause() {
        if (isPaused) return;
        isPaused = true;
        injectHandler.removeCallbacks(injectRunnable);
        injectHandler.removeCallbacks(idleRecenterRunnable);
        injectHandler.removeCallbacks(captureRunnable);
        injectScheduled = false;
        releaseMouseState();
        if (mouseLockView != null) {
            mouseLockView.setVisibility(View.GONE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.GONE);
            mouseLockView.post(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mouseLockView.releasePointerCapture();
                }
                mouseLockView.clearFocus();
            });
        }
    }

    public void resume() {
        if (destroyed) return;
        if (!isPaused) {
            checkAndRegainFocus();
            return;
        }
        isPaused = false;
        focusMissCount = 0;
        refreshScreenSize();
        if (mouseLockView != null) {
            mouseLockView.setVisibility(View.VISIBLE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
            mouseLockView.post(() -> {
                if (typingMode) return;
                mouseLockView.requestFocus();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mouseLockView.requestPointerCapture();
                }
            });
        }
    }

    public void checkAndRegainFocus() {
        if (destroyed || typingMode || mouseLockView == null || isPaused
                || mouseLockView.getVisibility() != View.VISIBLE) return;

        refreshScreenSize();

        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ok = mouseLockView.hasPointerCapture();
        } else {
            ok = mouseLockView.isFocused();
        }

        if (ok) {
            focusMissCount = 0;
            forceAttempts = 0;
            return;
        }

        // window ได้โฟกัสอยู่แล้วแต่ capture ยังไม่ติด: ขอ capture เฉยๆ ไม่แตะ flag
        if (mouseLockView.hasWindowFocus()) {
            focusMissCount = 0;
            mouseLockView.post(() -> {
                try {
                    mouseLockView.requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mouseLockView.requestPointerCapture();
                    }
                } catch (Exception ignored) {}
            });
            return;
        }

        focusMissCount++;
        long nowMs = android.os.SystemClock.uptimeMillis();
        // บังคับโฟกัสใหม่: ต้องหลุดต่อเนื่อง 2 รอบ ห่างกันอย่างน้อย 5 วิ และไม่เกิน 3 ครั้งจนกว่าจะได้โฟกัสจริง
        if (focusMissCount >= 2 && forceAttempts < 3 && nowMs - lastForceTime > 5000) {
            focusMissCount = 0;
            forceAttempts++;
            lastForceTime = nowMs;
            forceWindowRefocus();
        } else {
            mouseLockView.post(() -> {
                try {
                    mouseLockView.requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mouseLockView.requestPointerCapture();
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void forceWindowRefocus() {
        try {
            if (mouseLockParams == null || windowManager == null || mouseLockView == null) return;
            mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(mouseLockView, mouseLockParams);

            mouseLockView.post(() -> {
                try {
                    if (destroyed || isPaused || typingMode) return;
                    mouseLockParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(mouseLockView, mouseLockParams);
                    mouseLockView.requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mouseLockView.requestPointerCapture();
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ส่งตำแหน่งนิ้วเสมือนของโหมดมุมมอง
    // Hz = 0: ส่งทันทีทุก event ไม่รอ vsync ไม่รวม event
    // Hz > 0: ส่งทันทีถ้าพ้น interval แล้ว (leading) ถ้ายังไม่พ้นให้นัดส่งตำแหน่งล่าสุดตอนครบ interval (trailing)
    private void scheduleViewInject() {
        if (viewInjectHz <= 0) {
            if (injectScheduled) {
                injectHandler.removeCallbacks(injectRunnable);
                injectScheduled = false;
            }
            doViewInject();
            return;
        }

        long interval = 1000L / viewInjectHz;
        long wait = lastInjectUptime + interval - android.os.SystemClock.uptimeMillis();

        if (wait <= 0) {
            // พ้นช่วงแล้ว ส่งเลย (leading edge)
            if (injectScheduled) {
                injectHandler.removeCallbacks(injectRunnable);
                injectScheduled = false;
            }
            doViewInject();
        } else if (!injectScheduled) {
            // ยังไม่ถึงเวลา นัดส่งตำแหน่งล่าสุดตอนครบ interval (trailing)
            injectScheduled = true;
            injectHandler.postDelayed(injectRunnable, wait);
        }
    }

    private void doViewInject() {
        injectScheduled = false;
        if (destroyed || isPaused || !isMouseMode || mouseMoveToken == 0) return;
        lastInjectUptime = android.os.SystemClock.uptimeMillis();
        // ส่งพิกัดทศนิยม ไม่ปัดเป็นพิกเซลเต็ม ความเร็วจะไม่เป็นขั้นบันได
        TouchSimulator.simulateMoveF(mouseMoveToken, 2, (float) viewX, (float) viewY);
    }

    private void handlePointerEvent(MotionEvent e) {
        // ปล่อยสถานะคลิกขวาเมื่อปุ่มขวาไม่ได้ถูกกดแล้ว (ใช้ buttonState ของ event ที่ตามมา)
        int act = e.getActionMasked();
        if (rightDown && act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_BUTTON_PRESS
                && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0) {
            rightDown = false;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float sumDx = e.getX();
                float sumDy = e.getY();
                for (int h = 0; h < e.getHistorySize(); h++) {
                    sumDx += e.getHistoricalX(h);
                    sumDy += e.getHistoricalY(h);
                }

                float speed = (float) Math.hypot(sumDx, sumDy);
                // โหมดมุมมอง (ล็อคเมาส์): ใช้ความเร็วคงที่ ไม่มีความเร่ง กันช้าๆ เร็วๆ ไม่เท่ากัน
                // โหมดเคอร์เซอร์ปกติ: ยังมีความเร่งเหมือนเดิม
                float gain = isMouseMode
                        ? mouseSensitivity
                        : mouseSensitivity * (0.5f + Math.min(speed, 1f) * 0.0625f);

                if (isMouseMode) {
                    // ถ้านิ้วเมาส์ (ID 2) ถูกล้างไปแล้ว ให้ DOWN ใหม่อัตโนมัติ
                    if (mouseMoveToken == 0 || !TouchSimulator.isPointerDown(2)) {
                        viewX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                        viewY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;
                        mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
                    }

                    // [เพิ่ม] กันเหนียวเช่นเดียวกัน: ถ้านิ้วหลอกหลุดไปด้วยเหตุผลใดก็ตาม ให้แปะกลับที่จุดเริ่ม
                    if (anchorToken == 0 || !TouchSimulator.isPointerDown(ANCHOR_POINTER_ID)) {
                        float ax = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                        float ay = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;
                        anchorToken = TouchSimulator.simulateDown(ANCHOR_POINTER_ID, Math.round(ax), Math.round(ay));
                    }

                    // กันเหวี่ยงแรงเกินจนพิกัดกระโดดไกลในเฟรมเดียว (อาการ "ล็อค"/สะบัดกลับตอนหันไว)
                    // (250 เดิมตัดตอนสะบัดเร็วจนความเร็วไม่คงที่ จึงขยายออก)
                    final float MAX_FRAME_DELTA = 1500f;
                    float dx = Math.max(-MAX_FRAME_DELTA, Math.min(MAX_FRAME_DELTA, sumDx));
                    float dy = Math.max(-MAX_FRAME_DELTA, Math.min(MAX_FRAME_DELTA, sumDy));

                    if (VIEW_CONSTANT_ZONE) {
                        // ความเร็วคงที่ล้วนๆ ไม่คูณอะไรตามตำแหน่ง (ทั้ง X และ Y)
                        viewX += dx * gain * viewSensX;
                        viewY += dy * gain * viewSensY;
                    } else {
                        viewX += dx * gain * viewSensX * factorX((float) viewX);
                        // แกน Y ชดเชยตอนหลุดขอบจอ (สูตรเดิม)
                        viewY += dy * gain * viewSensY * offscreenFactorY((float) viewY, maxScreenHeight, OFFSCREEN_GAIN_Y);

                        // ตั้งเวลาพานิ้วกลับจุดเริ่ม (เฉพาะ X) ตอนเมาส์หยุดขยับ
                        injectHandler.removeCallbacks(idleRecenterRunnable);
                        injectHandler.postDelayed(idleRecenterRunnable, VIEW_IDLE_RECENTER_MS);
                    }

                    // ขอให้ระบบส่ง event เมาส์มาทันที ไม่รอรวมตามเฟรม (ก้าวเล็กลง ภาพลื่นขึ้น ไม่สะดุดเป็นช่วงๆ)
                    try {
                        mouseLockView.requestUnbufferedDispatch(e);
                    } catch (Exception ignored) {}

                    if (VIEW_CONSTANT_ZONE) {
                        float zx0 = maxScreenWidth * ZONE_X_MIN, zx1 = maxScreenWidth * ZONE_X_MAX;
                        float zy0 = maxScreenHeight * ZONE_Y_MIN, zy1 = maxScreenHeight * ZONE_Y_MAX;
                        float nx = (float) viewX, ny = (float) viewY;
                        boolean out = false;
                        if (viewX < zx0 || viewX > zx1) {
                            out = true;
                            // หมุนไปขวา -> วางใหม่ที่ซ้ายของโซน / หมุนไปซ้าย -> วางใหม่ที่ขวาของโซน
                            nx = viewX > zx1 ? zx0 + (zx1 - zx0) * ZONE_RESTART
                                             : zx1 - (zx1 - zx0) * ZONE_RESTART;
                        }
                        if (viewY < zy0 || viewY > zy1) {
                            out = true;
                            ny = viewY > zy1 ? zy0 + (zy1 - zy0) * ZONE_RESTART
                                             : zy1 - (zy1 - zy0) * ZONE_RESTART;
                        }
                        if (out) {
                            recenterTo(nx, ny);
                        } else {
                            scheduleViewInject();
                        }
                        // ตั้งเวลารีเซ็ตตอนเมาส์หยุด
                        injectHandler.removeCallbacks(idleRecenterRunnable);
                        injectHandler.postDelayed(idleRecenterRunnable, VIEW_IDLE_RECENTER_MS);
                        break;
                    }

                    final float startX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                    final float startY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;

                    boolean needRecenter;
                    if (VIEW_ALLOW_OFFSCREEN) {
                        // ปล่อยให้นิ้วเสมือนลากเลยขอบจอไปได้ ไม่รีเซ็ตทั้งแกน X และ Y (ไม่ยกนิ้ว ไม่วางใหม่เลย)
                        needRecenter = false;
                    } else {
                        viewX = Math.max(10f, Math.min(maxScreenWidth - 10f, viewX));
                        viewY = Math.max(10f, Math.min(maxScreenHeight - 10f, viewY));
                        needRecenter =
                                Math.abs(viewX - startX) > maxScreenWidth * VIEW_RANGE_X
                                || Math.abs(viewY - startY) > maxScreenHeight * VIEW_RANGE_Y
                                || viewX <= 40f || viewX >= maxScreenWidth - 40f
                                || viewY <= 40f || viewY >= maxScreenHeight - 40f;
                    }

                    if (needRecenter && !pressing) {
                        if (mouseMoveToken != 0) {
                            TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) viewX), Math.round((float) viewY));
                        }
                        viewX = startX;
                        viewY = startY;
                        mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
                    } else {
                        scheduleViewInject();
                    }
                } else {
                    // ===== โหมดเคอร์เซอร์ปกติ: แก้ให้ลื่น =====
                    cursorX += sumDx * gain;
                    cursorY += sumDy * gain;
                    cursorX = Math.max(0f, Math.min(maxScreenWidth - 1, cursorX));
                    cursorY = Math.max(0f, Math.min(maxScreenHeight - 1, cursorY));

                    // 1) ขอ event เมาส์ทันที ไม่รอรวมตามเฟรมจอ
                    try {
                        mouseLockView.requestUnbufferedDispatch(e);
                    } catch (Exception ignored) {}

                    // 2) ขยับเคอร์เซอร์ทันที ไม่รอ Choreographer
                    if (customCursorView != null) {
                        customCursorView.setTranslationX(cursorX);
                        customCursorView.setTranslationY(cursorY);
                    }

                    boolean uiHandled = eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_MOVE);
                    if (!uiHandled && mouseMoveToken != 0) {
                        // 3) ส่งพิกัดทศนิยม ลากแล้วไม่เป็นขั้นบันได
                        TouchSimulator.simulateMoveF(mouseMoveToken, 2, cursorX, cursorY);
                    }
                }
                break;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_DOWN:
                // คลิกขวา: ACTION_DOWN ของเมาส์จะมี buttonState ขวา (actionButton เป็น 0) ส่วน BUTTON_PRESS มี actionButton ขวา
                // ต้องแยกออกมาเป็นคลิกขวาทั้งสองแบบ ไม่งั้น DOWN จะถูกนับเป็นคลิกซ้าย (ไปกดนิ้ว/ไปตั้งเป็น MS_L)
                // และใช้ rightDown กันเรียก onRightClick ซ้ำ 2 ครั้งในการคลิกครั้งเดียว
                if (e.getActionButton() == MotionEvent.BUTTON_SECONDARY
                        || (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if (!rightDown) {
                        rightDown = true;
                        eventListener.onRightClick();
                    }
                    break;
                }
                // ล็อคเมาส์อยู่: กดซ้ายลง -> FloatingService กดค้างที่ปุ่มซึ่งตั้งเป็น MS_L
                if (isMouseMode) {
                    if (!pressing) {
                        pressing = true;
                        eventListener.onMouseLockedLeftDown();
                    }
                    break;
                }
                if (!isMouseMode && !pressing) {
                    pressing = true;
                    if (!eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_DOWN)) {
                        mouseMoveToken = TouchSimulator.simulateDown(2, (int) cursorX, (int) cursorY);
                    }
                }
                break;

            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_UP:
                if (isMouseMode && pressing && e.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
                    pressing = false;
                    // ปล่อยนิ้วที่กดค้างไว้
                    eventListener.onMouseLockedLeftUp();
                    break;
                }
                if (!isMouseMode && pressing && e.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
                    pressing = false;
                    eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_UP);
                    if (mouseMoveToken != 0) {
                        TouchSimulator.simulateUp(mouseMoveToken, 2, (int) cursorX, (int) cursorY);
                        mouseMoveToken = 0;
                    }
                }
                break;
        }
    }

    public void toggleMouseMode() {
        setMouseMode(!isMouseMode);
    }

    public void setMouseMode(boolean active) {
        if (destroyed) return;
        refreshScreenSize();
        boolean wasMouseMode = isMouseMode;
        isMouseMode = active;
        // กันนิ้วคลิกซ้ายค้างตอนสลับโหมด
        if (pressing && wasMouseMode) eventListener.onMouseLockedLeftUp();
        pressing = false;
        if (!isPaused) {
            mouseLockView.setVisibility(View.VISIBLE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
        }

        // แก้ช้า: เรียก updateViewLayout (binder call) เฉพาะเมื่อ flag ยังไม่ถูกตั้งจริงๆ
        if (mouseLockParams != null && windowManager != null
                && (mouseLockParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
            } catch (Exception ignored) {}
        }

        if (isMouseMode) {
            if (!wasMouseMode) {
                if (mouseMoveToken != 0) {
                    TouchSimulator.simulateUp(mouseMoveToken, 2, (int) cursorX, (int) cursorY);
                    mouseMoveToken = 0;
                }
                viewX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                viewY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;

                mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));

                // [เพิ่ม] แปะนิ้วหลอกตัวที่ 3 นิ่งๆ ที่จุดเดียวกับจุดเริ่มมุมมอง
                // ให้มีนิ้วครบ 3 เสมอตลอดช่วงล็อคเมาส์ (กันเกมตีความ "ยิง+ลากมุมมอง" (2 นิ้ว) เป็นท่า pinch/rotate)
                if (anchorToken == 0) {
                    anchorToken = TouchSimulator.simulateDown(ANCHOR_POINTER_ID,
                            Math.round((float) viewX), Math.round((float) viewY));
                }
            }

            // แก้ช้า: แค่ซ่อนเคอร์เซอร์ ไม่ลบ view ทิ้ง (ครั้งหน้าไม่ต้องสร้าง/โหลดรูปใหม่)
            if (customCursorView != null) customCursorView.setVisibility(View.GONE);

            if (!typingMode) requestCaptureFast();
        } else {
            if (wasMouseMode && mouseMoveToken != 0) {
                TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) viewX), Math.round((float) viewY));
                mouseMoveToken = 0;
            }
            // [เพิ่ม] ปล่อยนิ้วหลอกตอนออกจากโหมดล็อคเมาส์
            if (wasMouseMode && anchorToken != 0) {
                TouchSimulator.simulateUp(anchorToken, ANCHOR_POINTER_ID,
                        Math.round((float) viewX), Math.round((float) viewY));
                anchorToken = 0;
            }

            // ปลดล็อคเมาส์ -> เคอร์เซอร์กลับมาอยู่กลางจอ
            if (wasMouseMode) {
                cursorX = maxScreenWidth / 2f;
                cursorY = maxScreenHeight / 2f;
            }

            // สร้างเคอร์เซอร์เฉพาะครั้งแรก หลังจากนั้นใช้ตัวเดิมซ้ำ
            if (customCursorView == null) {
                customCursorView = new ImageView(context);
                int cursorId = context.getResources().getIdentifier("ic_cursor", "drawable", context.getPackageName());

                if (cursorId != 0) {
                    customCursorView.setImageResource(cursorId);
                } else {
                    GradientDrawable fallbackCursor = new GradientDrawable();
                    fallbackCursor.setShape(GradientDrawable.OVAL);
                    fallbackCursor.setColor(Color.parseColor("#FF5555"));
                    fallbackCursor.setStroke(4, Color.WHITE);
                    customCursorView.setBackground(fallbackCursor);
                }

                FrameLayout.LayoutParams cursorParams = new FrameLayout.LayoutParams(cursorSize, cursorSize);
                cursorParams.gravity = Gravity.TOP | Gravity.START;
                customCursorView.setLayoutParams(cursorParams);
                cursorLayer.addView(customCursorView);
            }

            customCursorView.setVisibility(typingMode ? View.GONE : View.VISIBLE);
            customCursorView.setTranslationX(cursorX);
            customCursorView.setTranslationY(cursorY);

            if (!typingMode) requestCaptureFast();
        }
    }

    // โหมดพิมพ์: ปล่อย pointer capture + ให้ overlay ไม่รับโฟกัส เกมจะได้รับแป้นตรงๆ
    // เคอร์เซอร์ของระบบ (เมาส์จริง) จะกลับมาเอง
    public void setTypingMode(boolean typing) {
        if (destroyed || typingMode == typing || mouseLockView == null) return;
        typingMode = typing;
        injectHandler.removeCallbacks(captureRunnable);
        try {
            if (typing) {
                injectHandler.removeCallbacks(injectRunnable);
                injectHandler.removeCallbacks(idleRecenterRunnable);
                injectScheduled = false;
                releaseMouseState();
                if (isMouseMode) setMouseMode(false);
                if (cursorLayer != null) cursorLayer.setVisibility(View.GONE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mouseLockView.releasePointerCapture();
                mouseLockView.clearFocus();
                mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mouseLockParams.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
            } else {
                mouseLockParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
                if (!isPaused) {
                    if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
                    if (customCursorView != null && !isMouseMode) customCursorView.setVisibility(View.VISIBLE);
                    requestCaptureFast();
                }
            }
        } catch (Exception ignored) {}
    }

    // ยกหน้าต่างเคอร์เซอร์ขึ้นบนสุด (เรียกหลังมีหน้าต่างใหม่เด้งขึ้นมา)
    public void bringCursorToFront() {
        if (destroyed || cursorLayer == null || windowManager == null) return;
        try {
            if (cursorLayer.getParent() != null) windowManager.removeViewImmediate(cursorLayer);
            windowManager.addView(cursorLayer, cursorLayerParams);
        } catch (Exception ignored) {}
    }

    public boolean isMouseActive() {
        return isMouseMode;
    }

    public void releaseMouseState() {
        rightDown = false;
        // ปล่อยนิ้วคลิกซ้ายที่กดค้างอยู่ (ถ้ามี)
        if (pressing && isMouseMode) {
            pressing = false;
            eventListener.onMouseLockedLeftUp();
        }
        if (mouseMoveToken != 0) {
            TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) viewX), Math.round((float) viewY));
            mouseMoveToken = 0;
        }
        // [เพิ่ม] ปล่อยนิ้วหลอกคู่กันทุกครั้งที่ปล่อยนิ้วมุมมอง
        if (anchorToken != 0) {
            TouchSimulator.simulateUp(anchorToken, ANCHOR_POINTER_ID, Math.round((float) viewX), Math.round((float) viewY));
            anchorToken = 0;
        }
    }

    public void updateSensitivity(float sensitivity) {
        this.mouseSensitivity = sensitivity;
    }

    public void updateCursorSize(int size) {
        this.cursorSize = size;
        if (customCursorView != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) customCursorView.getLayoutParams();
            params.width = cursorSize;
            params.height = cursorSize;
            customCursorView.setLayoutParams(params);
        }
    }

    public void destroy() {
        destroyed = true;
        injectHandler.removeCallbacks(injectRunnable);
        injectHandler.removeCallbacks(idleRecenterRunnable);
        injectHandler.removeCallbacks(captureRunnable);
        injectScheduled = false;
        // ไม่ inject UP ตรงนี้ (กัน main thread ค้างตอนปิด) FloatingService จะ CANCEL ทุกนิ้วให้เอง
        mouseMoveToken = 0;
        anchorToken = 0; // [เพิ่ม] เคลียร์ไว้กันค้าง (ไม่ inject UP ด้วยเหตุผลเดียวกับ mouseMoveToken ข้างบน)
        try {
            if (mouseLockView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mouseLockView.setOnCapturedPointerListener(null);
                    mouseLockView.releasePointerCapture();
                }
                mouseLockView.clearFocus();
            }
        } catch (Exception ignored) {}
        try {
            if (cursorLayer != null && cursorLayer.getParent() != null) {
                windowManager.removeView(cursorLayer);
            }
        } catch (Exception ignored) {}
        try {
            if (mouseLockView != null && mouseLockView.getParent() != null) {
                windowManager.removeView(mouseLockView);
            }
        } catch (Exception ignored) {}
    }
}