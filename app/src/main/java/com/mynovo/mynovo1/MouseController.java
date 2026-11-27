package com.mynovo.mynovo1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class MouseController {
    private static final float MOUSE_LOCK_ALPHA = 0.05f;
    private static final float CURSOR_LAYER_ALPHA = 0.40f;

    private static final boolean MOUSE_ACCEL = false;

    private static final int PTR_A = 2;
    private static final int PTR_B = 6;

    private static final boolean OVERLAP_RECENTER = true;

    private static final float VIEW_EDGE_MARGIN_X = 1000000f;
    private static final float VIEW_EDGE_MARGIN_Y = 1000000f;

    private static final float STEP_MAX_FRAC = 0.08f;
    private static final int STEP_MAX_COUNT = 12;

    private static final boolean VIEW_ALLOW_OFFSCREEN = true;

    private static final boolean VIEW_CONSTANT_ZONE = false;

    private static final float VIEW_Y_LIMIT_SCREENS = 1000000f;
    private static final float VIEW_X_LIMIT_SCREENS = 1000000f;

    // [คืนค่าเก่า] แกน X ครึ่งซ้ายช้าลงเหลือ 30% (เดิมในไฟล์ใหม่เป็น 1.0f)
    private static final float CONST_LEFT_HALF_GAIN = 0.3f;
    private static final float CONST_LEFT_HALF_X = 0.7f;
    private static final float CONST_LEFT_HALF_BAND = 0.5f;

    private static final float CONST_OFFSCREEN_GAIN_X = 0.15f;
    private static final float CONST_OFFSCREEN_GAIN_Y = 1.0f;
    private static final float CONST_OFFSCREEN_BAND = 0.25f;

    private static final float CONST_X_SPEED_SCALE = 0.25f;

    private static final float CONST_TAU_MS = 9f;

    private static final float DOWN_SAFE_X_MIN = 0.18f;
    private static final float DOWN_SAFE_X_MAX = 0.88f;
    private static final float DOWN_SAFE_Y_MIN = 0.16f;
    private static final float DOWN_SAFE_Y_MAX = 0.88f;

    private static final float VIEW_RANGE_X = 0.10f;
    private static final float VIEW_RANGE_Y = 0.12f;

    private static final boolean OFFSCREEN_COMPENSATE = false;
    private static final float OFFSCREEN_GAIN_X = 0.25f;
    private static final float OFFSCREEN_MIN_X = 0.35f;
    private static final float OFFSCREEN_GAIN_Y = 1.0f;
    private static final float OFFSCREEN_BAND = 0.75f;
    private static final float OFFSCREEN_BAND_Y = 0.04f;

    private static final float POST_GAP_MS = 100000f;
    private static final float POST_GAP_MAX_V = 0.8f;
    private static final float POST_GAP_CAP_DT_MS = 16f;

    private static final float CONST_MAX_RAW_SPEED_X = 0f;
    private static final float CONST_MAX_RAW_SPEED_Y = 0f;
    private static final float CONST_CAP_DT_MIN_MS = 2f;
    private static final float CONST_CAP_DT_MAX_MS = 16f;

    // ใช้กับโหมดไม่คงที่ (flick) เท่านั้น โหมดคงที่ inject ตาม vsync
    private static final int DEFAULT_VIEW_HZ = 250;

    // [แก้ไข] ฟิลเตอร์ใช้เฉพาะโหมดคงที่ เปิดเป็นค่าเริ่มต้น (ปิดได้ด้วย setMouseFilter(false, 0, 0))
    // smooth 0.3-0.6 ยิ่งสูงยิ่งลื่นแต่หน่วง
    private volatile boolean filterEnabled = true;
    private volatile float filterSmooth = 0.4f;
    private volatile float filterDeadzonePx = 0f;

    private static final float FILTER_FAST_SPEED = 0.25f;
    private static final float FILTER_SPIKE_RATIO = 6f;
    private static final float FILTER_SPIKE_MIN_PX = 120f;
    private static final float FILTER_RESET_GAP_MS = 120f;
    private static final float FILTER_AVG_ATTACK = 0.9f;
    private static final float FILTER_AVG_RELEASE = 0.2f;

    private float fPrevX, fPrevY, fAccX, fAccY, fAvgMag;
    private final float[] fOut = new float[2];

    private void resetMouseFilter() {
        fPrevX = fPrevY = fAccX = fAccY = fAvgMag = 0f;
    }

    // Result is stored in fOut[0], fOut[1].
    private void applyMouseFilter(float dx, float dy, float dtMs) {
        final float dt = Math.max(1f, Math.min(32f, dtMs));
        if (dtMs > FILTER_RESET_GAP_MS) resetMouseFilter();

        float rawMag = (float) Math.hypot(dx, dy);
        float speedT = Math.max(0f, Math.min(1f, (rawMag / dt) / FILTER_FAST_SPEED));

        float mag = rawMag;
        float cap = Math.max(FILTER_SPIKE_MIN_PX, fAvgMag * FILTER_SPIKE_RATIO);
        if (mag > cap && mag > 0f) {
            float s = cap / mag;
            dx *= s; dy *= s; mag = cap;
        }
        float avgAlpha = (rawMag > fAvgMag) ? FILTER_AVG_ATTACK : FILTER_AVG_RELEASE;
        fAvgMag += avgAlpha * (rawMag - fAvgMag);

        float t = speedT;
        float minAlpha = 1f - 0.85f * Math.max(0f, Math.min(1f, filterSmooth));
        float alpha = minAlpha + (1f - minAlpha) * t;
        fPrevX += alpha * (dx - fPrevX);
        fPrevY += alpha * (dy - fPrevY);
        float ox = fPrevX, oy = fPrevY;

        float dz = filterDeadzonePx;
        if (dz > 0f) {
            fAccX += ox; fAccY += oy;
            if (Math.hypot(fAccX, fAccY) < dz) {
                ox = 0f; oy = 0f;
            } else {
                ox = fAccX; oy = fAccY;
                fAccX = 0f; fAccY = 0f;
            }
        }
        fOut[0] = ox;
        fOut[1] = oy;
    }

    public void setMouseFilter(boolean enabled, float smooth, float deadzonePx) {
        filterEnabled = enabled;
        filterSmooth = Math.max(0f, Math.min(1f, smooth));
        filterDeadzonePx = Math.max(0f, deadzonePx);
        resetMouseFilter();
    }

    private float offscreenFactor(float v, float max, float gainOut) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f) return 1f;
        float t = d / Math.max(1f, OFFSCREEN_BAND * max);
        float g = Math.max(0.05f, gainOut);
        float f = 1f / (1f + (1f / g - 1f) * t);
        return Math.max(OFFSCREEN_MIN_X, f);
    }

    private float offscreenFactorY(float v, float max, float gainOut) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f) return 1f;
        float t = Math.min(1f, d / Math.max(1f, OFFSCREEN_BAND_Y * max));
        return 1f - (1f - gainOut) * t;
    }

    private float factorX(float x) {
        if (!OFFSCREEN_COMPENSATE) return 1f;
        return offscreenFactor(x, maxScreenWidth, OFFSCREEN_GAIN_X);
    }

    private float constLeftHalfFactorX(float x) {
        float mid = CONST_LEFT_HALF_X * maxScreenWidth;
        float band = Math.max(1f, CONST_LEFT_HALF_BAND * maxScreenWidth);
        float t = (x - (mid - band)) / (2f * band);
        t = Math.max(0f, Math.min(1f, t));
        t = t * t * (3f - 2f * t);
        return CONST_LEFT_HALF_GAIN + (1f - CONST_LEFT_HALF_GAIN) * t;
    }

    private float constOffscreenFactor(float v, float max, float gain) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f || gain >= 1f) return 1f;
        float t = Math.min(1f, d / Math.max(1f, CONST_OFFSCREEN_BAND * max));
        t = t * t * (3f - 2f * t);
        return 1f - (1f - gain) * t;
    }

    // [คืนค่าเก่า] X ช้าลงเมื่อออกนอกจอ (เดิมในไฟล์ใหม่เป็น 0f)
    private static final float CONST_X_DIST_SLOWDOWN_K = 0f;
    private static final float CONST_X_DIST_MIN = 1.0f;

    private float constOffscreenFactorX(float x, float max) {
        float d = x < 0f ? -x : (x > max ? x - max : 0f);
        if (d <= 0f) return 1f;
        float f = 1f / (1f + CONST_X_DIST_SLOWDOWN_K * (d / Math.max(1f, max)));
        return Math.max(CONST_X_DIST_MIN, f);
    }

    private final Context context;
    private final WindowManager windowManager;

    private FrameLayout mouseLockView;
    private WindowManager.LayoutParams mouseLockParams;
    private ImageView customCursorView;

    private FrameLayout cursorLayer;
    private WindowManager.LayoutParams cursorLayerParams;

    private boolean isMouseMode = false;
    private boolean isPaused = false;
    private boolean destroyed = false;

    private boolean typingMode = false;

    private float cursorX = 0f;
    private float cursorY = 0f;
    private float maxScreenWidth;
    private float maxScreenHeight;

    private float mouseSensitivity;
    private float viewGain;
    private int cursorSize;

    private long mouseMoveToken = 0;
    private int viewPtr = PTR_A;
    private boolean pressing = false;
    private boolean rightDown = false;
    private boolean prevSecondary = false;
    private boolean prevPrimary = false;

    private int lostPointerStreak = 0;

    private double viewX = 0.0;
    private double viewY = 0.0;

    // [เพิ่ม] ตำแหน่งที่ส่งจริง (ไล่ตาม viewX/viewY) เฉพาะโหมดคงที่
    private float sx = 0f, sy = 0f;
    private long smoothTok = 0;
    private long lastSmoothNs = 0;

    private boolean injectScheduled = false;

    private int viewInjectHz = DEFAULT_VIEW_HZ;
    private long lastInjectNs = 0;
    private final android.os.Handler injectHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable injectRunnable = this::doViewInject;

    private boolean frameQueued = false;
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameQueued = false;
            doViewInject();
        }
    };

    // callback ยิง ถูกโพสต์ออกจาก hot path ของ event เมาส์
    // ใช้ handler เดียวกันทั้ง Down/Up เพื่อให้ลำดับถูกต้องเสมอ
    private void fireLeftDown() {
        injectHandler.post(() -> {
            if (eventListener != null) eventListener.onMouseLockedLeftDown();
        });
    }

    private void fireLeftUp() {
        injectHandler.post(() -> {
            if (eventListener != null) eventListener.onMouseLockedLeftUp();
        });
    }

    private void cancelViewInject() {
        injectHandler.removeCallbacks(injectRunnable);
        injectScheduled = false;
        if (frameQueued) {
            frameQueued = false;
            try {
                Choreographer.getInstance().removeFrameCallback(frameCb);
            } catch (Exception ignored) {}
        }
    }

    private void recenterIfOutside() {
        if (mouseMoveToken == 0) return;

        final float mx = maxScreenWidth * VIEW_EDGE_MARGIN_X;
        final float my = maxScreenHeight * VIEW_EDGE_MARGIN_Y;
        final boolean outX = viewX < -mx || viewX > maxScreenWidth + mx;
        final boolean outY = viewY < -my || viewY > maxScreenHeight + my;
        if (!outX && !outY) return;

        float nx = (float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX));
        float ny = (float) Math.max(0.0, Math.min(maxScreenHeight - 1, viewY));
        if (outX) {
            nx = maxScreenWidth * (viewX > maxScreenWidth ? DOWN_SAFE_X_MIN : DOWN_SAFE_X_MAX);
        }
        if (outY) {
            ny = maxScreenHeight * (viewY > maxScreenHeight ? DOWN_SAFE_Y_MIN : DOWN_SAFE_Y_MAX);
        }

        final int oldPtr = viewPtr;
        final long oldTok = mouseMoveToken;
        final int oldX = Math.round((float) viewX);
        final int oldY = Math.round((float) viewY);
        final int newPtr = (oldPtr == PTR_A) ? PTR_B : PTR_A;

        if (OVERLAP_RECENTER) {
            long newTok = TouchSimulator.simulateDown(newPtr, Math.round(nx), Math.round(ny));
            if (newTok == 0) return;
            TouchSimulator.simulateUp(oldTok, oldPtr, oldX, oldY);
            mouseMoveToken = newTok;
        } else {
            TouchSimulator.simulateUp(oldTok, oldPtr, oldX, oldY);
            mouseMoveToken = TouchSimulator.simulateDown(newPtr, Math.round(nx), Math.round(ny));
            if (mouseMoveToken == 0) {
                viewPtr = newPtr;
                viewX = nx;
                viewY = ny;
                smoothTok = 0;
                return;
            }
        }
        viewPtr = newPtr;
        viewX = nx;
        viewY = ny;
        smoothTok = 0; // [เพิ่ม] recenter -> ให้ตำแหน่งที่ส่ง snap ตาม ไม่ไถข้ามจอ
    }

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

    private int focusMissCount = 0;
    private int forceAttempts = 0;
    private long lastForceTime = 0;

    private float targetBtnX = 0f;
    private float targetBtnY = 0f;
    private boolean hasCustomTarget = false;

    private float viewSensX = 1f;
    private float viewSensY = 1f;

    private volatile boolean viewFlickAccel = true;

    private volatile float lockMaxV = 0.8f;

    private volatile float bitRef = 0.8f;
    private volatile float bitGain = 0f;
    private volatile float bitMax = 4.0f;

    private long lastMoveNs = 0;

    public void setViewFlickAccel(boolean on) { viewFlickAccel = on; }
    public void toggleViewFlickAccel() { viewFlickAccel = !viewFlickAccel; }
    public boolean isViewFlickAccel() { return viewFlickAccel; }
    public void setLockMaxV(float v) { lockMaxV = Math.max(0.05f, v); }
    public void setBitParams(float ref, float gain, float max) {
        bitRef = Math.max(0.05f, ref);
        bitGain = Math.max(0f, gain);
        bitMax = Math.max(1f, max);
    }

    private final MouseEventListener eventListener;

    public interface MouseEventListener {
        boolean onMouseClickUI(float x, float y, int action);
        void onRightClick();
        boolean onKeyEvent(KeyEvent event);
        void onWindowFocusLost();
        void onMouseLockedLeftDown();
        void onMouseLockedLeftUp();

        // [ลูกกลิ้งเมาส์] vscroll บวก = เลื่อนขึ้น, ลบ = เลื่อนลง (หน่วยเป็นจำนวนคลิก)
        default void onMouseWheel(float x, float y, float vscroll) {}
    }

    public MouseController(Context context, WindowManager windowManager, float maxWidth, float maxHeight, int cursorSize, float sensitivity, MouseEventListener listener) {
        this.context = context;
        this.windowManager = windowManager;
        this.maxScreenWidth = maxWidth;
        this.maxScreenHeight = maxHeight;
        this.cursorSize = cursorSize;
        this.mouseSensitivity = sensitivity;
        this.viewGain = sensitivity;
        this.eventListener = listener;

        this.targetBtnX = maxWidth * 0.79f;
        this.targetBtnY = maxHeight * 0.66f;
        this.hasCustomTarget = true;

        initView();
    }

    public void setTargetButtonPosition(float x, float y) {
        this.targetBtnX = x;
        this.targetBtnY = y;
        this.hasCustomTarget = true;
    }

    private static final float VIEW_SENS_CURVE = 1.0f;
    private static final float VIEW_SENS_PIVOT_X = 0.02f;
    private static final float VIEW_SENS_PIVOT_Y = 0.04f;

    private static final float VIEW_SPEED_SCALE_X = 0.30f;
    private static final float VIEW_SPEED_SCALE_Y = 0.30f;

    private float softenViewSens(float s, float pivot, float scale) {
        if (s <= 0f) return 0f;
        return (float) (pivot * Math.pow(s / pivot, VIEW_SENS_CURVE)) * scale;
    }

    private volatile float viewTweak = 1f;
    private float rawSensX = 1f;
    private float rawSensY = 1f;
    private boolean sensInitialized = false;

    private void applyViewSens() {
        this.viewSensX = softenViewSens(rawSensX, VIEW_SENS_PIVOT_X, VIEW_SPEED_SCALE_X) * viewTweak;
        this.viewSensY = softenViewSens(rawSensY, VIEW_SENS_PIVOT_Y, VIEW_SPEED_SCALE_Y) * viewTweak;
    }

    public void setViewSensitivity(float sx, float sy) {
        rawSensX = sx;
        rawSensY = sy;
        sensInitialized = true;
        applyViewSens();
    }

    public void setViewTweak(float t) {
        viewTweak = Math.max(0.1f, Math.min(3f, t));
        if (sensInitialized) applyViewSens();
    }

    public float getViewTweak() { return viewTweak; }

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
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
        );
        mouseLockParams.gravity = Gravity.TOP | Gravity.START;
        mouseLockParams.x = 0;
        mouseLockParams.y = 0;
        applyNoInsets(mouseLockParams);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mouseLockParams.alpha = MOUSE_LOCK_ALPHA;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mouseLockParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        windowManager.addView(mouseLockView, mouseLockParams);

        cursorLayer = new FrameLayout(context);
        cursorLayerParams = new WindowManager.LayoutParams(
                Math.round(maxScreenWidth), Math.round(maxScreenHeight),
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        cursorLayerParams.gravity = Gravity.TOP | Gravity.START;
        cursorLayerParams.x = 0;
        cursorLayerParams.y = 0;
        applyNoInsets(cursorLayerParams);
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

    private void applyNoInsets(WindowManager.LayoutParams p) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                p.setFitInsetsTypes(0);
                p.setFitInsetsSides(0);
            } catch (Throwable ignored) {}
        }
    }

    private void updateOverlaySizes() {
        try {
            if (windowManager == null) return;
            if (cursorLayer != null && cursorLayerParams != null && cursorLayer.getParent() != null) {
                cursorLayerParams.width = Math.round(maxScreenWidth);
                cursorLayerParams.height = Math.round(maxScreenHeight);
                windowManager.updateViewLayout(cursorLayer, cursorLayerParams);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    public void refreshScreenSize() {
        try {
            if (windowManager == null) return;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            float w = dm.widthPixels;
            float h = dm.heightPixels;
            if (w <= 0 || h <= 0) return;

            if (Math.abs(w - maxScreenWidth) < 2f && Math.abs(h - maxScreenHeight) < 2f) return;

            float sxf = (maxScreenWidth > 0) ? w / maxScreenWidth : 1f;
            float syf = (maxScreenHeight > 0) ? h / maxScreenHeight : 1f;
            maxScreenWidth = w;
            maxScreenHeight = h;
            updateOverlaySizes();

            targetBtnX *= sxf; targetBtnY *= syf;
            cursorX = Math.max(0f, Math.min(w - 1, cursorX * sxf));
            cursorY = Math.max(0f, Math.min(h - 1, cursorY * syf));

            if (mouseMoveToken != 0) {
                TouchSimulator.simulateUp(mouseMoveToken, viewPtr, Math.round((float) viewX), Math.round((float) viewY));
                mouseMoveToken = 0;
                viewPtr = PTR_A;
            }
            smoothTok = 0;
            viewX *= sxf; viewY *= syf;
        } catch (Exception ignored) {}
    }

    public void pause() {
        if (isPaused) return;
        isPaused = true;
        cancelViewInject();
        injectHandler.removeCallbacks(captureRunnable);
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

    private void scheduleViewInject() {
        if (viewInjectHz <= 0 || !viewFlickAccel) {
            if (!frameQueued) {
                try {
                    frameQueued = true;
                    Choreographer.getInstance().postFrameCallback(frameCb);
                } catch (Exception e) {
                    frameQueued = false;
                    doViewInject();
                }
            }
            return;
        }

        long intervalNs = 1_000_000_000L / viewInjectHz;
        long waitNs = lastInjectNs + intervalNs - System.nanoTime();

        if (waitNs <= 0) {
            if (injectScheduled) {
                injectHandler.removeCallbacks(injectRunnable);
                injectScheduled = false;
            }
            doViewInject();
        } else if (!injectScheduled) {
            injectScheduled = true;
            injectHandler.postDelayed(injectRunnable, Math.max(1L, (waitNs + 999_999L) / 1_000_000L));
        }
    }

    private void doViewInject() {
        injectScheduled = false;
        if (destroyed || isPaused || !isMouseMode || mouseMoveToken == 0) return;

        final boolean constMode = !viewFlickAccel;
        long now = System.nanoTime();
        long intervalNs = (viewInjectHz > 0 && !constMode) ? 1_000_000_000L / viewInjectHz : 0L;
        lastInjectNs = (intervalNs == 0L || now - lastInjectNs > intervalNs * 2)
                ? now
                : lastInjectNs + intervalNs;

        float ox = (float) viewX, oy = (float) viewY;
        boolean more = false;

        if (constMode) {
            float dx = ox - sx, dy = oy - sy;
            boolean snap = smoothTok != mouseMoveToken
                    || Math.hypot(dx, dy) > maxScreenWidth * 0.3f; // down ใหม่ / recenter
            if (snap) {
                sx = ox;
                sy = oy;
                smoothTok = mouseMoveToken;
                lastSmoothNs = 0;
            } else {
                float dtMs = lastSmoothNs == 0 ? 8f : Math.min(50f, (now - lastSmoothNs) / 1e6f);
                lastSmoothNs = now;
                float a = 1f - (float) Math.exp(-dtMs / CONST_TAU_MS);
                sx += dx * a;
                sy += dy * a;
                more = Math.abs(ox - sx) > 0.5f || Math.abs(oy - sy) > 0.5f;
                if (!more) {
                    sx = ox;
                    sy = oy;
                    lastSmoothNs = 0;
                }
            }
            ox = sx;
            oy = sy;
        }

        TouchSimulator.simulateMoveF(mouseMoveToken, viewPtr, ox, oy);

        // เมาส์หยุดแล้วแต่ตำแหน่งที่ส่งยังไม่ถึงเป้า -> ส่งต่ออีกเฟรมจนถึง
        if (more) scheduleViewInject();
    }

    private void handlePointerEvent(MotionEvent e) {
        boolean secNow = (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
        if (secNow && !prevSecondary && !rightDown) {
            rightDown = true;
            eventListener.onRightClick();
        }
        if (!secNow) rightDown = false;
        prevSecondary = secNow;

        boolean priNow = (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0;
        int act0 = e.getActionMasked();
        if (isMouseMode && act0 != MotionEvent.ACTION_DOWN && act0 != MotionEvent.ACTION_BUTTON_PRESS
                && act0 != MotionEvent.ACTION_UP && act0 != MotionEvent.ACTION_BUTTON_RELEASE) {
            if (priNow && !prevPrimary && !pressing) {
                pressing = true;
                fireLeftDown();
            } else if (!priNow && prevPrimary && pressing) {
                pressing = false;
                fireLeftUp();
            }
        }
        prevPrimary = priNow;

        int act = e.getActionMasked();
        if (rightDown && act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_BUTTON_PRESS
                && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0) {
            rightDown = false;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_SCROLL:
                // [ลูกกลิ้งเมาส์] ทำงานเฉพาะโหมดปกติ (โหมด View ไม่ส่ง เพื่อไม่รบกวนการหมุนกล้อง)
                if (!isMouseMode && !typingMode) {
                    float vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vs != 0f) eventListener.onMouseWheel(cursorX, cursorY, vs);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                // ขอ event แบบไม่ถูก batch ตั้งแต่ต้น
                try {
                    mouseLockView.requestUnbufferedDispatch(e);
                } catch (Exception ignored) {}

                float sumDx = e.getX();
                float sumDy = e.getY();
                for (int h = 0; h < e.getHistorySize(); h++) {
                    sumDx += e.getHistoricalX(h);
                    sumDy += e.getHistoricalY(h);
                }

                float speed = (float) Math.hypot(sumDx, sumDy);
                float gain = isMouseMode
                        ? viewGain
                        : mouseSensitivity * (MOUSE_ACCEL
                                ? (0.5f + Math.min(speed, 1f) * 0.0625f)
                                : 0.5625f);

                if (isMouseMode) {
                    boolean tokenGone = mouseMoveToken == 0;
                    boolean freeX = !viewFlickAccel;
                    boolean reportedUp = !tokenGone && !freeX && !pressing && !TouchSimulator.isPointerDown(viewPtr);
                    if (reportedUp) {
                        lostPointerStreak++;
                    } else {
                        lostPointerStreak = 0;
                    }
                    if (tokenGone || lostPointerStreak >= 2) {
                        lostPointerStreak = 0;
                        if (!viewFlickAccel) {
                            viewX = Math.max(0.0, Math.min(maxScreenWidth - 1, viewX));
                            viewY = Math.max(0.0, Math.min(maxScreenHeight - 1, viewY));
                        } else {
                            viewX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                            viewY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;
                        }
                        mouseMoveToken = TouchSimulator.simulateDown(viewPtr, Math.round((float) viewX), Math.round((float) viewY));
                        smoothTok = 0;
                    }

                    long nowNs = System.nanoTime();
                    float rawDt = lastMoveNs == 0 ? 8f : (nowNs - lastMoveNs) / 1_000_000f;
                    lastMoveNs = nowNs;
                    float dtMsFull = Math.max(1f, Math.min(1000f, rawDt));

                    final float MAX_FRAME_DELTA = 1500f;
                    final float MAX_FRAME_DELTA_X = viewFlickAccel ? MAX_FRAME_DELTA : 1_000_000f;

                    float inDx = sumDx, inDy = sumDy;
                    if (filterEnabled && !viewFlickAccel) {
                        applyMouseFilter(sumDx, sumDy, dtMsFull);
                        inDx = fOut[0];
                        inDy = fOut[1];
                    }
                    float dx = Math.max(-MAX_FRAME_DELTA_X, Math.min(MAX_FRAME_DELTA_X, inDx));
                    float dy = Math.max(-MAX_FRAME_DELTA, Math.min(MAX_FRAME_DELTA, inDy));

                    if (!viewFlickAccel && dtMsFull > POST_GAP_MS) {
                        float capPx = POST_GAP_MAX_V * Math.min(dtMsFull, POST_GAP_CAP_DT_MS);
                        dx = Math.max(-capPx, Math.min(capPx, dx));
                        dy = Math.max(-capPx, Math.min(capPx, dy));
                    }

                    if (!viewFlickAccel) {
                        final float capDt = Math.max(CONST_CAP_DT_MIN_MS, Math.min(dtMsFull, CONST_CAP_DT_MAX_MS));
                        if (CONST_MAX_RAW_SPEED_X > 0f) {
                            final float capRawX = CONST_MAX_RAW_SPEED_X * capDt;
                            dx = Math.max(-capRawX, Math.min(capRawX, dx));
                        }
                        if (CONST_MAX_RAW_SPEED_Y > 0f) {
                            final float capRawY = CONST_MAX_RAW_SPEED_Y * capDt;
                            dy = Math.max(-capRawY, Math.min(capRawY, dy));
                        }
                    }

                    if (viewFlickAccel) {
                        float dtMs = Math.max(1f, Math.min(32f, dtMsFull));

                        float fvx = Math.abs(dx * gain * viewSensX) / dtMs;
                        if (fvx > bitRef && bitGain > 0f) {
                            float k = Math.min(bitMax, 1f + bitGain * (fvx / bitRef - 1f));
                            dx *= k;
                        }
                    }

                    final boolean useConst = VIEW_CONSTANT_ZONE || !viewFlickAccel;

                    if (useConst) {
                        final float mvX = dx * gain * viewSensX * CONST_X_SPEED_SCALE;
                        final float mvY = dy * gain * viewSensY;

                        final float needX = Math.abs(mvX) / Math.max(1f, maxScreenWidth * STEP_MAX_FRAC);
                        final float needY = Math.abs(mvY) / Math.max(1f, maxScreenHeight * STEP_MAX_FRAC);
                        final int steps = Math.max(1, Math.min(STEP_MAX_COUNT,
                                (int) Math.ceil(Math.max(needX, needY))));
                        final float stX = mvX / steps;
                        final float stY = mvY / steps;

                        final double xLim = maxScreenWidth * VIEW_X_LIMIT_SCREENS;
                        final double yLim = maxScreenHeight * VIEW_Y_LIMIT_SCREENS;

                        for (int i = 0; i < steps; i++) {
                               viewY += stY * constOffscreenFactor((float) viewY, maxScreenHeight, CONST_OFFSCREEN_GAIN_Y);
                               viewX += stX * constLeftHalfFactorX((float) viewX)
                                                 * constOffscreenFactorX((float) viewX, maxScreenWidth);

                            if (viewY < -yLim) viewY = -yLim;
                            else if (viewY > maxScreenHeight + yLim) viewY = maxScreenHeight + yLim;
                            if (viewX < -xLim) viewX = -xLim;
                            else if (viewX > maxScreenWidth + xLim) viewX = maxScreenWidth + xLim;

                            recenterIfOutside();
                        }

                        scheduleViewInject();
                        break;
                    }

                    viewX += dx * gain * viewSensX * factorX((float) viewX);
                    viewY += dy * gain * viewSensY * offscreenFactorY((float) viewY, maxScreenHeight, OFFSCREEN_GAIN_Y);

                    final float startX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                    final float startY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;

                    boolean needRecenter;
                    if (VIEW_ALLOW_OFFSCREEN) {
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
                            TouchSimulator.simulateUp(mouseMoveToken, viewPtr, Math.round((float) viewX), Math.round((float) viewY));
                        }
                        viewX = startX;
                        viewY = startY;
                        mouseMoveToken = TouchSimulator.simulateDown(viewPtr, Math.round((float) viewX), Math.round((float) viewY));
                        smoothTok = 0;
                    } else {
                        scheduleViewInject();
                    }
                } else {
                    cursorX += sumDx * gain;
                    cursorY += sumDy * gain;
                    cursorX = Math.max(0f, Math.min(maxScreenWidth - 1, cursorX));
                    cursorY = Math.max(0f, Math.min(maxScreenHeight - 1, cursorY));

                    if (customCursorView != null) {
                        customCursorView.setTranslationX(cursorX);
                        customCursorView.setTranslationY(cursorY);
                    }

                    boolean uiHandled = eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_MOVE);
                    if (!uiHandled && mouseMoveToken != 0) {
                        TouchSimulator.simulateMoveF(mouseMoveToken, viewPtr, cursorX, cursorY);
                    }
                }
                break;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_DOWN:
                final int ab = e.getActionButton();
                if (ab == MotionEvent.BUTTON_SECONDARY
                        || (ab == 0 && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0)) {
                    if (!rightDown) {
                        rightDown = true;
                        eventListener.onRightClick();
                    }
                    break;
                }
                if (isMouseMode) {
                    if (!pressing) {
                        pressing = true;
                        fireLeftDown();
                    }
                    break;
                }
                if (!isMouseMode && !pressing) {
                    pressing = true;
                    if (!eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_DOWN)) {
                        mouseMoveToken = TouchSimulator.simulateDown(viewPtr, (int) cursorX, (int) cursorY);
                    }
                }
                break;

            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_UP:
                if (isMouseMode && pressing && e.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
                    pressing = false;
                    fireLeftUp();
                    break;
                }
                if (!isMouseMode && pressing && e.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
                    pressing = false;
                    eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_UP);
                    if (mouseMoveToken != 0) {
                        TouchSimulator.simulateUp(mouseMoveToken, viewPtr, (int) cursorX, (int) cursorY);
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
        if (pressing && wasMouseMode) fireLeftUp();
        pressing = false;
        if (!isPaused) {
            mouseLockView.setVisibility(View.VISIBLE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
        }

        if (mouseLockParams != null && windowManager != null
                && (mouseLockParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
            } catch (Exception ignored) {}
        }

        if (isMouseMode) {
            if (!wasMouseMode) {
                lastMoveNs = 0;
                lostPointerStreak = 0;
                smoothTok = 0;
                lastSmoothNs = 0;
                resetMouseFilter();

                if (mouseMoveToken != 0) {
                    TouchSimulator.simulateUp(mouseMoveToken, viewPtr, (int) cursorX, (int) cursorY);
                    mouseMoveToken = 0;
                }
                viewPtr = PTR_A;
                viewX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                viewY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;

                mouseMoveToken = TouchSimulator.simulateDown(viewPtr, Math.round((float) viewX), Math.round((float) viewY));
            }

            if (customCursorView != null) customCursorView.setVisibility(View.GONE);

            if (!typingMode) requestCaptureFast();
        } else {
            if (wasMouseMode) cancelViewInject();

            if (wasMouseMode && mouseMoveToken != 0) {
                TouchSimulator.simulateUp(mouseMoveToken, viewPtr, Math.round((float) viewX), Math.round((float) viewY));
                mouseMoveToken = 0;
            }
            viewPtr = PTR_A;

            if (wasMouseMode) {
                cursorX = maxScreenWidth / 2f;
                cursorY = maxScreenHeight / 2f;
            }

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

    public void setTypingMode(boolean typing) {
        if (destroyed || typingMode == typing || mouseLockView == null) return;
        typingMode = typing;
        injectHandler.removeCallbacks(captureRunnable);
        try {
            if (typing) {
                cancelViewInject();
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
        prevSecondary = false;
        lostPointerStreak = 0;
        smoothTok = 0;
        lastSmoothNs = 0;
        resetMouseFilter();
        if (pressing && isMouseMode) {
            pressing = false;
            fireLeftUp();
        }
        if (mouseMoveToken != 0) {
            TouchSimulator.simulateUp(mouseMoveToken, viewPtr, Math.round((float) viewX), Math.round((float) viewY));
            mouseMoveToken = 0;
        }
        viewPtr = PTR_A;
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
        cancelViewInject();
        injectHandler.removeCallbacks(captureRunnable);
        mouseMoveToken = 0;
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
