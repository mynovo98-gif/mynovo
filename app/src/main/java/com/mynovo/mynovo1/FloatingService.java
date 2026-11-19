package com.mynovo.mynovo1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

public class FloatingService extends Service implements MouseController.MouseEventListener {

    public static final String ACTION_STOP = "com.mynovo.mynovo1.ACTION_STOP_FLOATING";

    // ใช้ร่วมกับ KeyBlockService เพื่อกันปุ่ม Windows ไม่ให้ออกหน้าหลัก
    public static volatile boolean blockMetaKey = false;
    public static volatile FloatingService instance = null;

    private WindowManager windowManager;

    private android.view.Display targetDisplay;
    private Context displayContext;

    // gameDisplayId: -1 = จอมือถือ (default), >0 = จอภายนอก (DeX)
    private volatile int gameDisplayId = -1;
    // forcedDisplayId: เป็นแค่ "คำใบ้" จาก Intent (ไม่ล็อกอีกต่อไป) ตัวจริงดูจากจอที่เกมรันอยู่จริง
    private volatile int forcedDisplayId = -1;

    // ปุ่มลัดเปิด/ปิดการแสดงปุ่มทั้งหมด (-1 = ยังไม่ตั้ง) เก็บค่าถาวรใน prefs
    private volatile int toggleBtnsKeyId = -1;
    private boolean isWaitingToggleKey = false;
    private Runnable cancelToggleKeyRunnable = null;
    private Button btnToggleKeyRef = null;

    // ===== [KB] จับคีย์บอร์ดแบบ exclusive (kbgrab ผ่าน InputServer / ShellServerClient) =====
    // kbGrabEnabled   : ผู้ใช้เปิดใช้ระบบนี้ (ตั้งค่าระบบ) เก็บถาวรใน prefs
    // kbEscaped       : ผู้ใช้กด Ctrl+Esc ปล่อยชั่วคราว (รีเซ็ตเมื่อกลับเข้าเกม หรือแตะปุ่มในตั้งค่า)
    // kbGrabRequested : ตอนนี้ขอ grab อยู่หรือไม่
    // kbGrabCount     : จำนวนคีย์บอร์ดที่ grab อยู่จริง (0 = ไม่สำเร็จ/ไม่มีคีย์บอร์ด)
    private volatile boolean kbGrabEnabled = true;
    private volatile boolean kbEscaped = false;
    private volatile boolean kbGrabRequested = false;
    private volatile int kbGrabCount = 0;
    private boolean settingsTextEditing = false;
    private Button btnKbGrabRef = null; // [FPS] ไม่ใช้แสดงในเมนูแล้ว แต่เก็บไว้กันโค้ดอื่นพัง
    private long kbDownTime = 0;
    // [แก้ lag] จำกัดความถี่การลองเชื่อม InputServer ใหม่
    private long lastKbReconnectAt = 0;

    // ===== [FPS] เปิด/ปิดการแสดง FPS ลอยหน้าจอ =====
    private FpsOverlay fpsOverlay;
    private RealFpsMonitor realFpsMonitor;
    private Button btnFpsToggleRef = null;

    private volatile int lastExtDisplayId = -1;
    private volatile int lastObservedReal = -1;

    private volatile int pendingExtId = -1;
    private volatile int pendingExtCount = 0;
    private volatile int pendingDefaultCount = 0;

    private boolean overlayInitialized = false;
    private boolean overlayBuilt = false;

    private android.hardware.display.DisplayManager.DisplayListener displayListener;
    private Runnable pendingDisplaySwitch = null;
    private static final long DISPLAY_SWITCH_DEBOUNCE_MS = 700;

    private FrameLayout editModeBackgroundView;
    private WindowManager.LayoutParams editBgParams;
    private long lastBgClickTime = 0;

    private View floatingView;
    private View menuView;
    private View addMenuView;
    private View iconSettingsView;
    private View cloudView;
    private View buttonSettingsView;
    private WindowManager.LayoutParams buttonSettingsParams;

    private WindowManager.LayoutParams floatingParams;
    private WindowManager.LayoutParams menuParams;
    private WindowManager.LayoutParams addMenuParams;
    private WindowManager.LayoutParams configParams;
    private WindowManager.LayoutParams cloudParams;

    private View warningDialogView = null;
    private WindowManager.LayoutParams warningParams;

    private String targetPackage = "";
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isChecking = false;
    private boolean isMenuShown = false;

    private volatile boolean destroyed = false;
    private volatile boolean killOnDestroy = false;
    private boolean targetSeen = false;
    private int ownAppCount = 0;
    private long awaySince = 0;
    private static final long AWAY_STOP_MS = 3000;

    private volatile boolean isTargetInForeground = true;

    private boolean isTypingMode = false;
    private boolean batchLoading = false;

    private List<View> mappingButtons = new ArrayList<>();
    private Map<View, Integer> buttonKeyMap = new HashMap<>();
    private Map<View, TextView> buttonTextViewMap = new HashMap<>();
    private Map<View, Integer> buttonSizeDpMap = new HashMap<>();
    private Map<View, Float> buttonAlphaMap = new HashMap<>();
    private Map<View, Float> buttonSensXMap = new HashMap<>();
    private Map<View, Float> buttonSensYMap = new HashMap<>();
    private Map<View, Integer> buttonHzMap = new HashMap<>();
    private Map<View, float[]> buttonFlickMap = new HashMap<>();

    private float[] flickCfg(View c) {
        float[] f = buttonFlickMap.get(c);
        if (f == null) {
            f = new float[]{1f, 0.8f, 0.8f, 0f, 4f};
            buttonFlickMap.put(c, f);
        }
        return f;
    }

    private Map<View, Long> activeTouchPointers = new HashMap<>();
    private Map<View, Runnable> rapidFireRunnables = new HashMap<>();

    private final Map<View, RapidTap> rapidTapMap = new HashMap<>();
    // [แก้ไข] เดิมเก็บเป็น "ครั้ง/วิ" ตอนนี้เก็บเป็น "ช่วงห่าง (ms)"
    private final Map<View, Integer> buttonIntervalMap = new HashMap<>();
    // [เพิ่ม] โหมดแตะรัวของแต่ละปุ่ม: false/ไม่มีค่า = กดค้างเพื่อรัว, true = กดเริ่ม กดอีกครั้งหยุด
    private final Map<View, Boolean> buttonRapidToggleMap = new HashMap<>();
    private final Map<View, Integer> buttonPressMsMap = new HashMap<>();
    private int rapidPtrCounter = 0;
    private View rapidMouseHeld = null;

    private int tapPtrCounter = 0;
    private static final int TAP_PTR_BASE = 40;
    private static final int TAP_PTR_POOL = 20;

    // [เพิ่ม] ปุ่มแตะ (NORMAL) ที่กำลังกดค้างอยู่ -> pointer id ที่ใช้
    private final Map<View, Integer> tapHeldPtr = new HashMap<>();

    private int allocTapPtr() {
        for (int i = 0; i < TAP_PTR_POOL; i++) {
            int p = TAP_PTR_BASE + i;
            if (!tapHeldPtr.containsValue(p)) return p;
        }
        return TAP_PTR_BASE;
    }

    private void releaseAllTapHolds() {
        for (Integer p : new ArrayList<>(tapHeldPtr.values())) TouchSimulator.simulateRelease(p);
        tapHeldPtr.clear();
    }

    private MouseController mouseController;
    private boolean isMouseMode = false;

    private float currentAlpha = 0.9f;
    private float mouseSensitivity = 4.0f;
    private int cursorSize;
    private float maxScreenWidth = 0f;
    private float maxScreenHeight = 0f;

    private int dpadSizeDp = 160;
    private int globalBtnSizeDp = 32;

    private static final float BTN_WINDOW_ALPHA = 0.39f;
    private static final float BTN_MIN_ALPHA = 0.4f;
    private float globalBtnAlpha = 1.0f;
    private int dpadSize;
    private DPadController dPadController;

    private View targetUiView = null;

    private boolean isWaitingForKey = false;
    private View pendingMappingButton = null;
    private Runnable cancelWaitingRunnable = null;
    private long keyWaitStartTime = 0;

    private long lastViewToggleTime = 0;
    private final Map<Integer, Long> lastTapAt = new HashMap<>();
    private long lastClickTime = 0;
    private static final long DOUBLE_CLICK_TIME_DELTA = 300;

    private static final int MOUSE_LEFT_POINTER = 3;
    private long mouseLeftToken = 0;
    private int mouseLeftX = 0;
    private int mouseLeftY = 0;

    private final HandlerThread monitorThread = new HandlerThread("AppMonitor");
    private Handler monitorHandler;

    private boolean buttonsHidden = false;

    private volatile String prevNavGesture = null;
    private volatile String prevNavDetail = null;
    private volatile boolean navSwitched = false;

    private static String cleanSetting(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.equals("null") || s.contains("\n")) return null;
        return s;
    }

    private void enableGestureNavBlocking() {
        if (navSwitched) return;
        String cur = cleanSetting(runShizukuShell("settings get global navigation_bar_gesture_while_hidden"));
        String det = cleanSetting(runShizukuShell("settings get global navigation_bar_gesture_detail_type"));
        prevNavGesture = (cur != null) ? cur : "0";
        prevNavDetail = det; 
        navSwitched = true;
        Log.i("NAV", "prev gesture=" + prevNavGesture + " detail=" + prevNavDetail);
        if (!"1".equals(cur)) {
            runShizukuShell("settings put global navigation_bar_gesture_while_hidden 1");
        }
        runShizukuShell("settings put global navigation_bar_gesture_detail_type 0");
    }

    private void enableGestureNav() {
        new Thread(this::enableGestureNavBlocking).start();
    }

    // ===== เลือก FPS (ล็อกรีเฟรชเรตหน้าจอ) 60/90/120 =====
    private volatile int fpsTargetHz = 0; // 0 = ไม่ล็อก
    private boolean rateSwitched = false;
    private String prevPeakRate = null, prevMinRate = null;
    private final Button[] btnRateOpt = new Button[3];
    private static final int[] RATE_STEPS = {60, 90, 120};

    private void applyRefreshRate(final int hz) {
        new Thread(() -> {
            if (!rateSwitched) {
                prevPeakRate = cleanSetting(runShizukuShell("settings get system peak_refresh_rate"));
                prevMinRate = cleanSetting(runShizukuShell("settings get system min_refresh_rate"));
                rateSwitched = true;
            }
            runShizukuShell("settings put system peak_refresh_rate " + hz + ".0");
            runShizukuShell("settings put system min_refresh_rate " + hz + ".0");
        }).start();
    }

    private void restoreRefreshRate() {
        if (!rateSwitched) return;
        rateSwitched = false;
        if (prevPeakRate != null) runShizukuShell("settings put system peak_refresh_rate " + prevPeakRate);
        else runShizukuShell("settings delete system peak_refresh_rate");
        if (prevMinRate != null) runShizukuShell("settings put system min_refresh_rate " + prevMinRate);
        else runShizukuShell("settings delete system min_refresh_rate");
    }

    private void refreshRateButtons() {
        for (int i = 0; i < 3; i++) {
            if (btnRateOpt[i] != null) styleModeButton(btnRateOpt[i], fpsTargetHz == RATE_STEPS[i]);
        }
    }

    private void onRateButtonClick(int hz) {
        if (fpsTargetHz == hz) { // แตะซ้ำ = ยกเลิกล็อก
            fpsTargetHz = 0;
            new Thread(this::restoreRefreshRate).start();
        } else {
            fpsTargetHz = hz;
            applyRefreshRate(hz);
        }
        try {
            getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                    .putInt("fps_target_hz", fpsTargetHz).apply();
        } catch (Exception ignored) {}
        refreshRateButtons();
    }

    private void restoreNavBlocking() {
        restoreRefreshRate();
        if (!navSwitched) return;
        navSwitched = false;
        String v = prevNavGesture;
        String d = prevNavDetail;
        prevNavGesture = null;
        prevNavDetail = null;
        runShizukuShell("settings put global navigation_bar_gesture_while_hidden " + (v != null ? v : "0"));
        if (d != null) {
            runShizukuShell("settings put global navigation_bar_gesture_detail_type " + d);
        }
        Log.i("NAV", "restored gesture=" + v + " detail=" + d);
    }

    // เปิด KeyBlockService (Accessibility) ผ่าน Shizuku อัตโนมัติ
    private void enableKeyBlockService() {
        try {
            String cn = getPackageName() + "/" + KeyBlockService.class.getName();
            String cur = cleanSetting(runShizukuShell("settings get secure enabled_accessibility_services"));
            if (cur == null || !cur.contains(cn)) {
                String nv = (cur == null) ? cn : cur + ":" + cn;
                runShizukuShell("settings put secure enabled_accessibility_services " + nv);
            }
            runShizukuShell("settings put secure accessibility_enabled 1");
        } catch (Throwable ignored) {}
    }

    private int dpToPx(int dp) {
        Context c = displayContext != null ? displayContext : this;
        float density = c.getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    private android.view.Display findTargetDisplay() {
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        int want = gameDisplayId;
        if (want > 0) {
            android.view.Display d = dm.getDisplay(want);
            if (d != null) return d;
            return null;
        }
        return dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
    }

    private Context buildDisplayContext(android.view.Display d) {
        Context c = createDisplayContext(d);
        if (Build.VERSION.SDK_INT >= 30) {
            try { return c.createWindowContext(overlayType(), null); } catch (Throwable ignored) {}
        }
        return c;
    }

    private boolean onExternalDisplay() {
        return targetDisplay != null
                && targetDisplay.getDisplayId() != android.view.Display.DEFAULT_DISPLAY;
    }

    private String presetKey(boolean external) {
        return targetPackage + (external ? "_preset_dex" : "_preset");
    }

    private String settingsPrefsName() {
        return onExternalDisplay() ? "MynovoSettingsDex" : "MynovoSettings";
    }

    private String mappingPrefsName() {
        return onExternalDisplay() ? "MynovoMappingPrefsDex" : "MynovoMappingPrefs";
    }

    private boolean displayIdWarned = false;

    private void pushDisplayIdToSimulator(int id) {
        try {
            TouchSimulator.class.getMethod("setDisplayId", int.class).invoke(null, id);
        } catch (Throwable t) {
            Log.w("DEX", "TouchSimulator.setDisplayId not available: " + t);
            if (id != android.view.Display.DEFAULT_DISPLAY && !displayIdWarned) {
                displayIdWarned = true;
                try {
                    Toast.makeText(this,
                            "⚠️ TouchSimulator ไม่มี setDisplayId: ทัชจะไม่ไปที่จอ DeX",
                            Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            }
        }
    }

    private int getDrawableId(String resName, int fallbackResId) {
        try {
            int id = getResources().getIdentifier(resName, "drawable", getPackageName());
            return (id != 0) ? id : fallbackResId;
        } catch (Exception e) {
            return fallbackResId;
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private GradientDrawable makeButtonBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor("#FF00BCD4"));
        g.setStroke(dpToPx(3), Color.WHITE);
        return g;
    }

    private void rapidStart(View b) {
        if (b == null) return;
        RapidTap rt = rapidTapMap.get(b);
        if (rt == null) {
            rt = new RapidTap(10 + (rapidPtrCounter++ % 15));
            rapidTapMap.put(b, rt);
        }
        int[] loc = new int[2];
        b.getLocationOnScreen(loc);
        rt.setPosition(loc[0] + b.getWidth() / 2f, loc[1] + b.getHeight() / 2f);
        Integer iv = buttonIntervalMap.get(b);
        rt.setIntervalMs(iv != null ? iv : 100);
        Integer pms = buttonPressMsMap.get(b);
        rt.setPressMs(pms != null ? pms : 25);
        rt.start();
    }

    private void rapidStop(View b) {
        if (b == null) return;
        RapidTap rt = rapidTapMap.get(b);
        if (rt != null) rt.stop();
    }

    private boolean isRapidToggle(View b) {
        Boolean t = buttonRapidToggleMap.get(b);
        return t != null && t;
    }

    // กดปุ่ม: โหมดกดค้าง = เริ่มรัว / โหมดกดเริ่ม-กดหยุด = สลับเปิดปิด
    private void rapidPress(View b) {
        if (b == null) return;
        if (isRapidToggle(b)) {
            RapidTap rt = rapidTapMap.get(b);
            if (rt != null && rt.isRunning()) rapidStop(b);
            else rapidStart(b);
        } else {
            rapidStart(b);
        }
    }

    // ปล่อยปุ่ม: โหมดกดค้าง = หยุด / โหมดกดเริ่ม-กดหยุด = ไม่ทำอะไร (รัวต่อ)
    private void rapidRelease(View b) {
        if (b == null) return;
        if (!isRapidToggle(b)) rapidStop(b);
    }

    private void stopAllRapidTaps() {
        for (RapidTap rt : rapidTapMap.values()) rt.stop();
        rapidMouseHeld = null;
    }

    private void removeRapidData(View b) {
        RapidTap rt = rapidTapMap.remove(b);
        if (rt != null) rt.release();
        buttonIntervalMap.remove(b);
        buttonRapidToggleMap.remove(b);
        buttonPressMsMap.remove(b);
    }

    private void clearAllRapidData() {
        for (RapidTap rt : rapidTapMap.values()) rt.release();
        rapidTapMap.clear();
        buttonIntervalMap.clear();
        buttonRapidToggleMap.clear();
        buttonPressMsMap.clear();
        rapidMouseHeld = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            SharedPreferences cp = getSharedPreferences("MynovoCore", MODE_PRIVATE);
            toggleBtnsKeyId = cp.getInt("toggle_btns_key", -1);
            lastExtDisplayId = cp.getInt("last_ext_display", -1);
            kbGrabEnabled = cp.getBoolean("kb_grab_enabled", true); // [KB]
            fpsTargetHz = cp.getInt("fps_target_hz", 0);
        } catch (Exception ignored) {}
    }

    private boolean buildOverlay() {
        if (overlayBuilt) return true;
        android.view.Display d = findTargetDisplay();
        if (d == null) return false; 
        try {
            targetDisplay = d;
            displayContext = buildDisplayContext(targetDisplay);
            windowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
            overlayBuilt = true; 
            int[] initSize = realScreenSize();
            maxScreenWidth = initSize[0];
            maxScreenHeight = initSize[1];

            TouchSimulator.setContext(this);
            ShellServerClient.setKeyListener(kbListener); // [KB]
            pushDisplayIdToSimulator(targetDisplay.getDisplayId());
            loadSettings();

            enableGestureNav();
            if (fpsTargetHz > 0) applyRefreshRate(fpsTargetHz);
            new Thread(this::enableKeyBlockService).start();
            blockMetaKey = true;
            startForegroundServiceForAndroid16();

            createEditModeBackground();
            createFloatingIcon();
            createMenuWindow();
            createAddMenuWindow();
            createIconSettingsWindow();
            createCloudWindow();

            mouseController = new MouseController(this, windowManager, maxScreenWidth, maxScreenHeight, cursorSize, mouseSensitivity, this);

            // [FPS] สร้าง overlay FPS และกู้สถานะเปิด/ปิดที่เคยตั้งไว้
            fpsOverlay = new FpsOverlay(displayContext, windowManager);
            try {
                boolean wantFps = getSharedPreferences("MynovoCore", MODE_PRIVATE)
                        .getBoolean("fps_overlay_enabled", false);
                if (wantFps) startFpsFeature();
            } catch (Exception ignored) {}

            monitorThread.start();
            monitorHandler = new Handler(monitorThread.getLooper());

            checkFirstTimeLaunch();
            registerDisplayListener();
            updateKbGrab(); // [KB]
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    // [แก้ไข] ถ้าจอ DeX ยังเข้าถึงไม่ได้ จะพยายามเปิดจอซ้ำหลายรอบ และเมื่อหมดเวลาจะสร้างบนจอมือถือชั่วคราว
    // จากนั้นตัวมอนิเตอร์จะย้ายกลับไป DeX ให้เองเมื่อเห็นว่าเกมรันอยู่ที่ DeX จริง
    private void tryBuild(final int attempt) {
        if (destroyed) return;
        if (buildOverlay()) {
            startAppMonitor();
            loadPresetFromCloud();
        } else if (attempt < 40) {
            if (attempt == 6 || attempt == 16 || attempt == 28) {
                final int want = gameDisplayId;
                if (want > 0) new Thread(() -> ensureDisplayAccessible(want)).start();
            }
            handler.postDelayed(() -> tryBuild(attempt + 1), 300);
        } else {
            gameDisplayId = -1;
            probeActive = false;
            if (buildOverlay()) {
                startAppMonitor();
                loadPresetFromCloud();
            }
        }
    }

    // [แก้ไข] ไม่เปลี่ยน gameDisplayId เองตอนมีจอใหม่ถูกเพิ่ม (เดิมทำให้ overlay กระโดดไป DeX ทั้งที่เกมอยู่บนมือถือ)
    private void registerDisplayListener() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm == null) return;
            displayListener = new android.hardware.display.DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {
                    scheduleDisplayReevaluation();
                }
                @Override public void onDisplayRemoved(int displayId) {
                    if (displayId == gameDisplayId) {
                        gameDisplayId = -1;
                        probeActive = false;
                        pendingExtId = -1;
                        pendingExtCount = 0;
                        pendingDefaultCount = 0;
                    }
                    scheduleDisplayReevaluation();
                }
                @Override public void onDisplayChanged(int displayId) {
                    scheduleDisplayReevaluation();
                }
            };
            dm.registerDisplayListener(displayListener, handler);
        } catch (Exception e) {}
    }

    private void scheduleDisplayReevaluation() {
        if (destroyed) return;
        if (pendingDisplaySwitch != null) handler.removeCallbacks(pendingDisplaySwitch);
        pendingDisplaySwitch = () -> {
            pendingDisplaySwitch = null;
            if (destroyed) return;
            try {
                android.view.Display best = findTargetDisplay();
                if (best != null && targetDisplay != null && best.getDisplayId() == targetDisplay.getDisplayId()) {
                    return;
                }
                if (best != null && (targetDisplay == null || best.getDisplayId() != targetDisplay.getDisplayId())) {
                    rebuildOverlayForDisplay(best);
                }
            } catch (Exception e) {}
        };
        handler.postDelayed(pendingDisplaySwitch, DISPLAY_SWITCH_DEBOUNCE_MS);
    }

    private void rebuildOverlayForDisplay(android.view.Display newDisplay) {
        if (destroyed || newDisplay == null) return;
        if (targetDisplay != null && targetDisplay.getDisplayId() == newDisplay.getDisplayId()) return;

        try {
            if (!targetPackage.isEmpty() && !batchLoading) savePresetToCloud();
            saveSettings();

            if (dPadController != null) { dPadController.release(); dPadController = null; }
            stopAllRapidTaps();
            TouchSimulator.releaseTouch();
            mouseLeftToken = 0;
            if (isWaitingForKey) cancelKeyWait();
            if (isWaitingToggleKey) cancelToggleKeyWait();
            closeSubWindows();
            closeButtonSettings();
            closeWarningDialog();

            isMenuShown = false;
            isTypingMode = false;
            isMouseMode = false;
            targetUiView = null;
            swallowMouseUp = false;

            removeViewSafely(editModeBackgroundView);
            removeViewSafely(floatingView);
            removeViewSafely(menuView);
            removeViewSafely(addMenuView);
            removeViewSafely(iconSettingsView);
            removeViewSafely(cloudView);
            for (View btn : new ArrayList<>(mappingButtons)) removeViewSafely(btn);

            mappingButtons.clear();
            buttonKeyMap.clear();
            buttonTextViewMap.clear();
            buttonSizeDpMap.clear();
            buttonAlphaMap.clear();
            buttonSensXMap.clear();
            buttonSensYMap.clear();
            buttonHzMap.clear();
            buttonFlickMap.clear();
            clearAllRapidData();

            if (mouseController != null) {
                mouseController.destroy();
                mouseController = null;
            }

            targetDisplay = newDisplay;
            displayContext = buildDisplayContext(targetDisplay);
            windowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
            int[] sz = realScreenSize();
            maxScreenWidth = sz[0];
            maxScreenHeight = sz[1];
            pushDisplayIdToSimulator(targetDisplay.getDisplayId());
            loadSettings();

            createEditModeBackground();
            createFloatingIcon();
            createMenuWindow();
            createAddMenuWindow();
            createIconSettingsWindow();
            createCloudWindow();

            mouseController = new MouseController(this, windowManager, maxScreenWidth, maxScreenHeight,
                    cursorSize, mouseSensitivity, this);

            if (!targetPackage.isEmpty()) {
                handler.post(this::loadPresetFromCloud);
            }

            if (!isTargetInForeground) {
                handler.post(() -> applyForegroundChange(false));
            }
            updateKbGrab(); // [KB]
        } catch (Exception e) {}
    }

    private void removeViewSafely(View v) {
        try {
            if (v != null && v.getParent() != null && windowManager != null) {
                windowManager.removeView(v);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onMouseClickUI(float x, float y, int action) {
        return dispatchTouchToUi(x, y, action);
    }

    @Override
    public void onMouseLockedLeftDown() {
        if (destroyed || isEditModeActive() || !isTargetInForeground || !isMouseMode) return;

        View fireBtn = findFireButton();
        if (fireBtn != null) {
            int[] ml = new int[2];
            fireBtn.getLocationOnScreen(ml);
            mouseLeftX = ml[0] + fireBtn.getWidth() / 2;
            mouseLeftY = ml[1] + fireBtn.getHeight() / 2;
            mouseLeftToken = TouchSimulator.simulateDown(MOUSE_LEFT_POINTER, mouseLeftX, mouseLeftY);
            return;
        }

        if (mouseLeftToken != 0) return;
        View mapped = findMouseLeftButton();
        if (mapped == null) return;
        if ("RAPID_FIRE".equals(String.valueOf(mapped.getTag()))) {
            rapidMouseHeld = mapped;
            rapidPress(mapped);
            return;
        }
        int[] ml = new int[2];
        mapped.getLocationOnScreen(ml);
        mouseLeftX = ml[0] + mapped.getWidth() / 2;
        mouseLeftY = ml[1] + mapped.getHeight() / 2;
        mouseLeftToken = TouchSimulator.simulateDown(MOUSE_LEFT_POINTER, mouseLeftX, mouseLeftY);
    }

    @Override
    public void onMouseLockedLeftUp() {
        if (rapidMouseHeld != null) {
            rapidRelease(rapidMouseHeld);
            rapidMouseHeld = null;
            return;
        }
        if (!isMouseMode || mouseLeftToken == 0) return;
        long token = mouseLeftToken;
        mouseLeftToken = 0;
        TouchSimulator.simulateUp(token, MOUSE_LEFT_POINTER, mouseLeftX, mouseLeftY);
    }

    @Override
    public void onRightClick() {
        if (destroyed) return;
        if (isWaitingForKey && pendingMappingButton != null) {
            assignMouseRightToPending();
            return;
        }

        final int rightId = makeKeyId(60002, 0);
        boolean handled = false;
        for (Map.Entry<View, Integer> e : new ArrayList<>(buttonKeyMap.entrySet())) {
            Integer v = e.getValue();
            View b = e.getKey();
            if (v == null || b == null || v.intValue() != rightId) continue;
            String tag = String.valueOf(b.getTag());
            if ("DPAD".equals(tag)) continue;
            handled = true;
            if (isEditModeActive() || !isTargetInForeground) continue;
            if ("HIDE_MOUSE".equals(tag)) {
                toggleViewModeFromKey();
            } else if ("MOUSE_LOCK".equals(tag)) {
                toggleMouseLock();
            } else {
                if (tapDebounced(60002)) continue;
                int[] loc = new int[2];
                b.getLocationOnScreen(loc);
                TouchSimulator.simulateClick(loc[0] + b.getWidth() / 2, loc[1] + b.getHeight() / 2);
            }
        }
        if (handled) return;

        if (!hasViewKeyAssigned()) toggleMouseLock();
    }

    private View findFireButton() {
        for (Map.Entry<View, Integer> e : buttonKeyMap.entrySet()) {
            View b = e.getKey();
            if (b != null && "FIRE".equals(String.valueOf(b.getTag()))) {
                return b;
            }
        }
        return null;
    }

    private void assignMouseRightToPending() {
        if (pendingMappingButton == null) return;
        final int id = makeKeyId(60002, 0);
        for (Map.Entry<View, Integer> entry : buttonKeyMap.entrySet()) {
            if (entry.getKey() != pendingMappingButton && entry.getValue() != null
                    && entry.getValue() == id) {
                Toast.makeText(this, "ไม่สามารถตั้งปุ่มซ้ำได้!", Toast.LENGTH_SHORT).show();
                cancelKeyWait();
                return;
            }
        }
        finishAssign(id, "");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        return handleKey(event);
    }

    @Override
    public void onWindowFocusLost() {
        releaseAllTapHolds();
        if (dPadController != null) dPadController.release();
        stopAllRapidTaps();
        onMouseLockedLeftUp();
    }

    private void startForegroundServiceForAndroid16() {
        String CHANNEL_ID = "MynovoOverlayService";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Blue Mouse Pro", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent stopIntent = new Intent(this, FloatingService.class).setAction(ACTION_STOP);
            android.app.PendingIntent stopPi = android.app.PendingIntent.getService(
                    this, 0, stopIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "ปิดโปรแกรม", stopPi).build());
        }

        Notification notification = builder
                .setContentTitle("Blue Mouse Pro ทำงานอยู่")
                .setContentText("ระบบ Keymapper พร้อมใช้งาน")
                .setSmallIcon(getDrawableId("app_b", android.R.drawable.ic_menu_compass))
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    public void showWarningDialog(String titleText, String messageText, Runnable onConfirm) {
        if (destroyed) return;
        try {
            if (warningDialogView != null && warningDialogView.getParent() != null) {
                windowManager.removeView(warningDialogView);
            }

            LinearLayout dialogLayout = new LinearLayout(this);
            dialogLayout.setOrientation(LinearLayout.VERTICAL);
            dialogLayout.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#1E2235"));
            bg.setCornerRadius(dpToPx(20));
            bg.setStroke(dpToPx(1), Color.parseColor("#3B4261"));
            dialogLayout.setBackground(bg);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(titleText);
            tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
            tvTitle.setTextSize(16f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setPadding(0, 0, 0, dpToPx(10));
            dialogLayout.addView(tvTitle);

            TextView tvMessage = new TextView(this);
            tvMessage.setText(messageText);
            tvMessage.setTextColor(Color.parseColor("#D0D5DD"));
            tvMessage.setTextSize(13f);
            tvMessage.setLineSpacing(dpToPx(3), 1.0f);
            tvMessage.setPadding(0, 0, 0, dpToPx(20));
            dialogLayout.addView(tvMessage);

            LinearLayout btnLayout = new LinearLayout(this);
            btnLayout.setOrientation(LinearLayout.HORIZONTAL);
            btnLayout.setGravity(Gravity.END);

            Button btnCancel = new Button(this);
            btnCancel.setText("ปิด");
            btnCancel.setTextColor(Color.parseColor("#FF5555"));
            btnCancel.setTextSize(13f);
            btnCancel.setAllCaps(false);
            GradientDrawable cancelBg = new GradientDrawable();
            cancelBg.setColor(Color.parseColor("#2D1B22"));
            cancelBg.setCornerRadius(dpToPx(10));
            btnCancel.setBackground(cancelBg);
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dpToPx(90), dpToPx(38));
            cancelParams.rightMargin = dpToPx(10);
            btnCancel.setLayoutParams(cancelParams);
            btnCancel.setOnClickListener(v -> closeWarningDialog());
            btnLayout.addView(btnCancel);

            Button btnConfirm = new Button(this);
            btnConfirm.setText("ตกลง");
            btnConfirm.setTextColor(Color.WHITE);
            btnConfirm.setTextSize(13f);
            btnConfirm.setAllCaps(false);
            GradientDrawable confirmBg = new GradientDrawable();
            confirmBg.setColor(Color.parseColor("#50FA7B"));
            confirmBg.setCornerRadius(dpToPx(10));
            btnConfirm.setBackground(confirmBg);
            LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(dpToPx(90), dpToPx(38));
            btnConfirm.setLayoutParams(confirmParams);
            btnConfirm.setOnClickListener(v -> {
                closeWarningDialog();
                if (onConfirm != null) onConfirm.run();
            });
            btnLayout.addView(btnConfirm);

            dialogLayout.addView(btnLayout);
            warningDialogView = dialogLayout;

            warningParams = new WindowManager.LayoutParams(
                    dpToPx(320), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            warningParams.gravity = Gravity.CENTER;

            windowManager.addView(warningDialogView, warningParams);
            bringCursorToFront();
        } catch (Exception e) {}
    }

    private void closeWarningDialog() {
        try {
            if (warningDialogView != null && warningDialogView.getParent() != null) {
                windowManager.removeView(warningDialogView);
            }
        } catch (Exception ignored) {}
        warningDialogView = null;
    }

    private void checkFirstTimeLaunch() {
        try {
            SharedPreferences prefs = getSharedPreferences("MynovoSettings", MODE_PRIVATE);
            boolean isFirstTime = prefs.getBoolean("is_first_launch", true);

            if (isFirstTime) {
                handler.postDelayed(() -> {
                    if (destroyed) return;
                    showWarningDialog(
                        "👋 ยินดีต้อนรับสู่ Blue Mouse Pro!",
                        "เพื่อให้แอปทำงานได้อย่างเต็มประสิทธิภาพ:\n\n" +
                        "1️⃣ ตั้งค่าแบตเตอรี่เป็น 'ไม่จำกัด' (Unrestricted)\n" +
                        "2️⃣ ตรวจสอบสิทธิ์ Shizuku ให้เรียบร้อย",
                        () -> prefs.edit().putBoolean("is_first_launch", false).apply()
                    );
                }, 1500);
            }
        } catch (Exception e) {}
    }

    private void createEditModeBackground() {
        try {
            editModeBackgroundView = new FrameLayout(this);
            editModeBackgroundView.setBackgroundColor(Color.parseColor("#26000000"));

            editBgParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            editBgParams.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                editBgParams.alpha = 0.3f;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                editBgParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            editModeBackgroundView.setVisibility(View.GONE);
            editModeBackgroundView.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (isWaitingForKey && pendingMappingButton != null) {
                        if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                            assignMouseRightToPending();
                        } else {
                            assignMouseLeftToPending();
                        }
                        return true;
                    }
                    if (isWaitingForKey) cancelKeyWait();

                    long clickTime = System.currentTimeMillis();
                    if (clickTime - lastBgClickTime < DOUBLE_CLICK_TIME_DELTA) {
                        int btnSize = dpToPx(globalBtnSizeDp);
                        int spawnX = (int) event.getRawX() - (btnSize / 2);
                        int spawnY = (int) event.getRawY() - (btnSize / 2);
                        addNewMappingButton("NORMAL", spawnX, spawnY, true, -1, false);
                        lastBgClickTime = 0;
                    } else {
                        lastBgClickTime = clickTime;
                    }
                }
                return true;
            });

            windowManager.addView(editModeBackgroundView, editBgParams);
        } catch (Exception e) {}
    }

    private void unblockUntrustedTouches() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        new Thread(() -> {
            try {
                java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null, new Object[]{
                        new String[]{"sh", "-c", "settings put global block_untrusted_touches 0"},
                        null, null});
                p.waitFor();
            } catch (Throwable t) {}
        }).start();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(settingsPrefsName(), MODE_PRIVATE);
        currentAlpha = Math.max(0.4f, prefs.getFloat("alpha", 0.9f));
        mouseSensitivity = prefs.getFloat("sensitivity", 4.0f);
        cursorSize = prefs.getInt("cursorSize", dpToPx(36));
        dpadSizeDp = prefs.getInt("dpadSizeDp", 160);
        dpadSize = dpToPx(dpadSizeDp);
        globalBtnSizeDp = Math.max(20, Math.min(96, prefs.getInt("btnSizeDp", 32)));
        globalBtnAlpha = Math.max(BTN_MIN_ALPHA, Math.min(1.0f, prefs.getFloat("btnViewAlpha", 1.0f)));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(settingsPrefsName(), MODE_PRIVATE);
        prefs.edit()
             .putFloat("alpha", currentAlpha)
             .putFloat("sensitivity", mouseSensitivity)
             .putInt("cursorSize", cursorSize)
             .putInt("dpadSizeDp", dpadSizeDp)
             .putInt("btnSizeDp", globalBtnSizeDp)
             .putFloat("btnViewAlpha", globalBtnAlpha)
             .apply();
    }

    private void rememberExternal(int id) {
        if (id > 0 && id != lastExtDisplayId) {
            lastExtDisplayId = id;
            try {
                getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                        .putInt("last_ext_display", id).apply();
            } catch (Exception ignored) {}
        }
    }

    // real: 0 = มือถือ, >0 = จอภายนอก, <0 = ไม่รู้  -> คืนจอที่จะวางปุ่ม
    // [แก้ไข] เอาระบบสลับจอออกแล้ว คืนค่าจอที่ตรวจเจอตรง ๆ
    private int mapDisplay(int real) {
        if (real > 0) rememberExternal(real);
        return real;
    }

    private int detectInitialDisplaySync(final String pkg) {
        if (pkg == null || pkg.isEmpty()) return android.view.Display.DEFAULT_DISPLAY;
        final int[] result = {android.view.Display.DEFAULT_DISPLAY};
        Thread t = new Thread(() -> {
            try {
                int q = queryGameDisplay(pkg);
                if (q >= 0) {
                    int m = mapDisplay(q);
                    if (m > 0) result[0] = m;
                }
            } catch (Throwable ignored) {}
        });
        t.start();
        try { t.join(400); } catch (InterruptedException ignored) {}
        return result[0];
    }

    private volatile boolean displayConfirmed = false;

    // [แก้ไข] คืนค่า "จอที่เกมกำลัง resumed อยู่จริง" เท่านั้น
    //   0  = จอมือถือ, >0 = จอ DeX/จอภายนอก, -1 = ไม่รู้/กำกวม
    // เดิมใช้ ActivityRecord ที่ไม่ได้ resumed (record เก่าค้างในอีกจอ) มาตัดสิน ทำให้สลับจอผิด
    private int queryGameDisplay(String pkg) {
        int a = queryDisplayViaWindow(pkg);
        if (a >= 0) {
            Log.i("DISPLAY", "window -> " + a);
            return a;
        }
        int b = queryDisplayViaActivities(pkg);
        Log.i("DISPLAY", "activities -> " + b);
        return b;
    }

    // [ใหม่] วิธีหลัก: dumpsys window displays ระบุจอชัดเจนด้วย "mDisplayId=N" และ mFocusedApp/mCurrentFocus ต่อจอ
    private int queryDisplayViaWindow(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty() || !Shizuku.pingBinder()) return -1;
            if (shizukuNewProcess == null) {
                shizukuNewProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                shizukuNewProcess.setAccessible(true);
            }
            Process proc = (Process) shizukuNewProcess.invoke(null, new Object[]{
                    new String[]{"sh", "-c",
                            "dumpsys window displays | grep -E 'mDisplayId=|mFocusedApp=|mCurrentFocus='"},
                    null, null});
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("u\\d+\\s+([A-Za-z0-9_.]+)/");
            java.util.regex.Pattern dispPat = java.util.regex.Pattern.compile("mDisplayId=(\\d+)");
            int cur = -1;
            int other = -1;
            boolean onZero = false;
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                boolean isFocus = line.contains("mFocusedApp=") || line.contains("mCurrentFocus=");
                java.util.regex.Matcher dm2 = dispPat.matcher(line);
                if (dm2.find()) {
                    cur = Integer.parseInt(dm2.group(1));
                    if (!isFocus) continue;
                }
                if (!isFocus) continue;
                java.util.regex.Matcher m = pat.matcher(line);
                if (!m.find()) continue;
                if (cur >= 0 && pkg.equals(m.group(1))) {
                    if (cur == 0) onZero = true;
                    else other = cur;
                }
            }
            try { reader.close(); } catch (Exception ignored) {}
            try { proc.waitFor(); } catch (Exception ignored) {}

            if (other > 0 && !onZero) return other;
            if (onZero && other <= 0) return 0;
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // วิธีสำรอง: dumpsys activity activities
    private int queryDisplayViaActivities(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty() || !Shizuku.pingBinder()) return -1;
            if (shizukuNewProcess == null) {
                shizukuNewProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                shizukuNewProcess.setAccessible(true);
            }
            Process proc = (Process) shizukuNewProcess.invoke(null, new Object[]{
                    new String[]{"sh", "-c", "dumpsys activity activities | grep -E 'Display #|ResumedActivity'"},
                    null, null});
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("u\\d+\\s+([A-Za-z0-9_.]+)/");
            java.util.regex.Pattern dispPat = java.util.regex.Pattern.compile("Display #(\\d+)");
            int curDisplay = -1;
            int other = -1;
            boolean onZero = false;
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher dm2 = dispPat.matcher(line);
                if (dm2.find()) {
                    curDisplay = Integer.parseInt(dm2.group(1));
                    continue;
                }
                java.util.regex.Matcher m = pat.matcher(line);
                if (!m.find()) continue;
                boolean isGlobalLine = line.contains("topResumedActivity") || line.contains("mLastResumed");
                if (!isGlobalLine && curDisplay >= 0 && pkg.equals(m.group(1))) {
                    if (curDisplay == 0) onZero = true;
                    else other = curDisplay;
                }
            }
            try { reader.close(); } catch (Exception ignored) {}
            try { proc.waitFor(); } catch (Exception ignored) {}

            if (other > 0 && !onZero) return other;
            if (onZero && other <= 0) return 0;
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private volatile boolean probeActive = false;

    private String runShizukuShell(String cmd) {
        try {
            if (!Shizuku.pingBinder()) return null;
            if (shizukuNewProcess == null) {
                shizukuNewProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                shizukuNewProcess.setAccessible(true);
            }
            Process p = (Process) shizukuNewProcess.invoke(null, new Object[]{
                    new String[]{"sh", "-c", cmd + " 2>&1"}, null, null});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            try { r.close(); } catch (Exception ignored) {}
            try { p.waitFor(); } catch (Exception ignored) {}
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureDisplayAccessible(int id) {
        try {
            if (id <= 0) return;
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm == null || dm.getDisplay(id) != null) return;

            runShizukuShell("am start --display " + id
                    + " --activity-no-animation -n " + getPackageName()
                    + "/com.mynovo.mynovo1.DisplayProbeActivity");
            probeActive = true;

            long end = android.os.SystemClock.uptimeMillis() + 3000;
            while (android.os.SystemClock.uptimeMillis() < end && !destroyed) {
                if (dm.getDisplay(id) != null) return;
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }
        } catch (Throwable t) {}
    }

    // [แก้ไข] ลำดับการเลือกจอตอนเริ่ม:
    //   1) ดูว่าเกม resumed อยู่จอไหนจริง (อ่านตรงกัน 2 ครั้งติดกัน) -> ใช้จอนั้น
    //   2) ถ้ายังไม่เห็นเกม -> ใช้จอที่ Intent ส่งมาเป็นคำใบ้ (GAME_DISPLAY)
    //   3) ถ้าไม่มีคำใบ้ -> จอมือถือ
    private void waitForGameDisplayThenBuild(final String pkg) {
        new Thread(() -> {
            final int hint = forcedDisplayId;
            int id = -1;
            int last = -2;
            int same = 0;

            long deadline = android.os.SystemClock.uptimeMillis() + 4000;
            while (!destroyed && android.os.SystemClock.uptimeMillis() < deadline) {
                int q = queryGameDisplay(pkg);
                if (q >= 0) {
                    if (q == last) same++;
                    else { last = q; same = 1; }
                    if (same >= 2) { id = q; break; }
                }
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            }

            if (id >= 0) { lastObservedReal = id; id = mapDisplay(id); }
            else id = hint;

            if (id > 0 && !destroyed) {
                ensureDisplayAccessible(id);
            }

            final int finalId = id;
            handler.post(() -> {
                if (destroyed) return;
                displayConfirmed = finalId >= 0;
                gameDisplayId = (finalId > 0) ? finalId : -1;
                tryBuild(0);
            });
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            killOnDestroy = true;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent.hasExtra("GAME_PACKAGE")) {
            targetPackage = intent.getStringExtra("GAME_PACKAGE");
            if (targetPackage == null) targetPackage = "";
            
            forcedDisplayId = intent.getIntExtra("GAME_DISPLAY", -1);
            targetSeen = false;
            ownAppCount = 0;
            awaySince = 0;
            pendingExtId = -1;
            pendingExtCount = 0;
            pendingDefaultCount = 0;

            unblockUntrustedTouches();

            if (!overlayInitialized) {
                overlayInitialized = true;
                try { startForegroundServiceForAndroid16(); } catch (Exception ignored) {}
                waitForGameDisplayThenBuild(targetPackage);
                return START_NOT_STICKY;
            }

            if (forcedDisplayId > 0) {
                gameDisplayId = forcedDisplayId;
            } else if (forcedDisplayId == 0) {
                gameDisplayId = -1;
            } else {
                int detected = detectInitialDisplaySync(targetPackage);
                gameDisplayId = (detected != android.view.Display.DEFAULT_DISPLAY) ? detected : -1;
            }
            
            if (targetDisplay != null) {
                android.view.Display want = findTargetDisplay();
                if (want != null && want.getDisplayId() != targetDisplay.getDisplayId()) {
                    rebuildOverlayForDisplay(want);
                }
            }

            startAppMonitor();
            handler.post(this::loadPresetFromCloud);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mouseController != null) mouseController.refreshScreenSize();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        killOnDestroy = true;
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isEditModeActive() {
        return isMenuShown
                || (addMenuView != null && addMenuView.getVisibility() == View.VISIBLE)
                || (iconSettingsView != null && iconSettingsView.getVisibility() == View.VISIBLE)
                || (cloudView != null && cloudView.getVisibility() == View.VISIBLE)
                || (buttonSettingsView != null && buttonSettingsView.getParent() != null)
                || isWaitingForKey;
    }

    // [แก้ไข] ซ่อนปุ่มได้เฉพาะเมื่อมีการตั้งปุ่มลัดไว้ในตั้งค่าระบบ (เอาปุ่ม "ซ่อนปุ่ม" บนจอออกแล้ว)
    private void applyButtonsHiddenState() {
        if (destroyed || !isTargetInForeground) return;
        boolean hide = buttonsHidden && !isEditModeActive() && toggleBtnsKeyId != -1;
        int want = hide ? View.INVISIBLE : View.VISIBLE;
        for (View b : mappingButtons) {
            if (b == null) continue;
            if (b.getVisibility() != want) b.setVisibility(want);
        }
    }

    private void toggleButtonsHidden() {
        if (destroyed || isEditModeActive() || !isTargetInForeground) return;
        buttonsHidden = !buttonsHidden;
        applyButtonsHiddenState();
    }

    // ===== ปุ่มลัดเปิด/ปิดการแสดงปุ่มทั้งหมด =====

    private String toggleKeyButtonText() {
        if (isWaitingToggleKey) return "⌨️ กดปุ่มที่ต้องการ... (BACK = ยกเลิก)";
        return "🔘 ปุ่มลัด: " + (toggleBtnsKeyId == -1
                ? "ยังไม่ตั้ง (แตะเพื่อเลือก)" : labelOfId(toggleBtnsKeyId));
    }

    private void refreshToggleKeyButton() {
        if (btnToggleKeyRef != null) btnToggleKeyRef.setText(toggleKeyButtonText());
    }

    private void startToggleKeyWait() {
        if (isWaitingForKey) cancelKeyWait();
        isWaitingToggleKey = true;
        refreshToggleKeyButton();
        if (menuView != null) menuView.requestFocus();
        if (cancelToggleKeyRunnable != null) handler.removeCallbacks(cancelToggleKeyRunnable);
        cancelToggleKeyRunnable = this::cancelToggleKeyWait;
        handler.postDelayed(cancelToggleKeyRunnable, 10000);
    }

    private void cancelToggleKeyWait() {
        if (cancelToggleKeyRunnable != null) handler.removeCallbacks(cancelToggleKeyRunnable);
        cancelToggleKeyRunnable = null;
        isWaitingToggleKey = false;
        refreshToggleKeyButton();
    }

    private void setToggleKey(int id) {
        toggleBtnsKeyId = id;
        try {
            getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                    .putInt("toggle_btns_key", id).apply();
        } catch (Exception ignored) {}
        if (id == -1) {
            buttonsHidden = false;
            applyButtonsHiddenState();
        }
        refreshToggleKeyButton();
    }

    private void assignToggleKey(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && e.getScanCode() == 0) {
            cancelToggleKeyWait();
            return;
        }
        if (dpadKeyOf(e) != -1) {
            Toast.makeText(this, "W A S D ล็อคไว้สำหรับ D-Pad", Toast.LENGTH_SHORT).show();
            cancelToggleKeyWait();
            return;
        }
        for (Integer v : buttonKeyMap.values()) {
            if (v != null && keyMatches(v, e)) {
                Toast.makeText(this, "ปุ่มนี้ถูกใช้กับปุ่มบนหน้าจอแล้ว", Toast.LENGTH_SHORT).show();
                cancelToggleKeyWait();
                return;
            }
        }
        cancelToggleKeyWait();
        setToggleKey(makeKeyId(e.getKeyCode(), e.getScanCode()));
        Toast.makeText(this, "✅ ตั้งปุ่มลัดเป็น " + labelOfId(toggleBtnsKeyId),
                Toast.LENGTH_SHORT).show();
    }

    // ===== [KB] จับคีย์บอร์ดแบบ exclusive =====

    private static final android.util.SparseIntArray SCAN_TO_KEYCODE = new android.util.SparseIntArray();
    static {
        SparseFill.fill(SCAN_TO_KEYCODE);
    }

    // ตารางแปลง Linux scancode -> Android keycode (เฉพาะปุ่มคีย์บอร์ดมาตรฐาน)
    private static final class SparseFill {
        static void fill(android.util.SparseIntArray m) {
            m.put(1, KeyEvent.KEYCODE_ESCAPE);
            for (int i = 0; i < 9; i++) m.put(2 + i, KeyEvent.KEYCODE_1 + i);
            m.put(11, KeyEvent.KEYCODE_0);
            m.put(12, KeyEvent.KEYCODE_MINUS);
            m.put(13, KeyEvent.KEYCODE_EQUALS);
            m.put(14, KeyEvent.KEYCODE_DEL);
            m.put(15, KeyEvent.KEYCODE_TAB);
            int[] r1 = {KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
                    KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I,
                    KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P};
            for (int i = 0; i < r1.length; i++) m.put(16 + i, r1[i]);
            m.put(26, KeyEvent.KEYCODE_LEFT_BRACKET);
            m.put(27, KeyEvent.KEYCODE_RIGHT_BRACKET);
            m.put(28, KeyEvent.KEYCODE_ENTER);
            m.put(29, KeyEvent.KEYCODE_CTRL_LEFT);
            int[] r2 = {KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
                    KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K,
                    KeyEvent.KEYCODE_L};
            for (int i = 0; i < r2.length; i++) m.put(30 + i, r2[i]);
            m.put(39, KeyEvent.KEYCODE_SEMICOLON);
            m.put(40, KeyEvent.KEYCODE_APOSTROPHE);
            m.put(41, KeyEvent.KEYCODE_GRAVE);
            m.put(42, KeyEvent.KEYCODE_SHIFT_LEFT);
            m.put(43, KeyEvent.KEYCODE_BACKSLASH);
            int[] r3 = {KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
                    KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_M};
            for (int i = 0; i < r3.length; i++) m.put(44 + i, r3[i]);
            m.put(51, KeyEvent.KEYCODE_COMMA);
            m.put(52, KeyEvent.KEYCODE_PERIOD);
            m.put(53, KeyEvent.KEYCODE_SLASH);
            m.put(54, KeyEvent.KEYCODE_SHIFT_RIGHT);
            m.put(55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY);
            m.put(56, KeyEvent.KEYCODE_ALT_LEFT);
            m.put(57, KeyEvent.KEYCODE_SPACE);
            m.put(58, KeyEvent.KEYCODE_CAPS_LOCK);
            for (int i = 0; i < 10; i++) m.put(59 + i, KeyEvent.KEYCODE_F1 + i);
            m.put(69, KeyEvent.KEYCODE_NUM_LOCK);
            m.put(70, KeyEvent.KEYCODE_SCROLL_LOCK);
            for (int i = 0; i < 3; i++) m.put(71 + i, KeyEvent.KEYCODE_NUMPAD_7 + i);
            m.put(74, KeyEvent.KEYCODE_NUMPAD_SUBTRACT);
            for (int i = 0; i < 3; i++) m.put(75 + i, KeyEvent.KEYCODE_NUMPAD_4 + i);
            m.put(78, KeyEvent.KEYCODE_NUMPAD_ADD);
            for (int i = 0; i < 3; i++) m.put(79 + i, KeyEvent.KEYCODE_NUMPAD_1 + i);
            m.put(82, KeyEvent.KEYCODE_NUMPAD_0);
            m.put(83, KeyEvent.KEYCODE_NUMPAD_DOT);
            m.put(87, KeyEvent.KEYCODE_F11);
            m.put(88, KeyEvent.KEYCODE_F12);
            m.put(96, KeyEvent.KEYCODE_NUMPAD_ENTER);
            m.put(97, KeyEvent.KEYCODE_CTRL_RIGHT);
            m.put(98, KeyEvent.KEYCODE_NUMPAD_DIVIDE);
            m.put(99, KeyEvent.KEYCODE_SYSRQ);
            m.put(100, KeyEvent.KEYCODE_ALT_RIGHT);
            m.put(102, KeyEvent.KEYCODE_MOVE_HOME);
            m.put(103, KeyEvent.KEYCODE_DPAD_UP);
            m.put(104, KeyEvent.KEYCODE_PAGE_UP);
            m.put(105, KeyEvent.KEYCODE_DPAD_LEFT);
            m.put(106, KeyEvent.KEYCODE_DPAD_RIGHT);
            m.put(107, KeyEvent.KEYCODE_MOVE_END);
            m.put(108, KeyEvent.KEYCODE_DPAD_DOWN);
            m.put(109, KeyEvent.KEYCODE_PAGE_DOWN);
            m.put(110, KeyEvent.KEYCODE_INSERT);
            m.put(111, KeyEvent.KEYCODE_FORWARD_DEL);
            m.put(119, KeyEvent.KEYCODE_BREAK);
            m.put(125, KeyEvent.KEYCODE_META_LEFT);
            m.put(126, KeyEvent.KEYCODE_META_RIGHT);
            m.put(127, KeyEvent.KEYCODE_MENU);
        }
    }

    // เฟรมที่ได้จาก kbgrab (ถูกเรียกจากเธรดอ่านของ ShellServerClient จึงส่งต่อเข้า main handler ทุกครั้ง)
    private final ShellServerClient.KeyListener kbListener = new ShellServerClient.KeyListener() {
        @Override public void onKey(final int scanCode, final int value) {
            // [แก้ lag] ทิ้งเฟรม key-repeat (value==2) ตั้งแต่ต้นทาง ไม่ต้องส่งเข้า main thread
            // (ปุ่มที่ map ไว้ใช้แค่ down/up อยู่แล้ว)
            if (value == 2) return;
            handler.post(() -> onKbKey(scanCode, value));
        }
        @Override public void onEscape() {
            handler.post(() -> onKbEscape());
        }
        @Override public void onGrabState(final int grabbedCount) {
            handler.post(() -> {
                kbGrabCount = grabbedCount;
                refreshKbGrabButton();
            });
        }
        @Override public void onDisconnected() {
            handler.post(() -> {
                kbGrabCount = 0;
                refreshKbGrabButton();
            });
        }
    };

    private void onKbKey(int scanCode, int value) {
        if (destroyed || !kbGrabRequested) return;
        if (value < 0 || value > 2) return;
        int kc = SCAN_TO_KEYCODE.get(scanCode, KeyEvent.KEYCODE_UNKNOWN);
        long now = android.os.SystemClock.uptimeMillis();
        if (value == 1 || kbDownTime == 0) kbDownTime = now;
        int action = (value == 0) ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN;
        int repeat = (value == 2) ? 1 : 0;
        KeyEvent ev = new KeyEvent(kbDownTime, now, action, kc, repeat, 0, 0, scanCode);
        handleKey(ev);
    }

    // ผู้ใช้กด Ctrl+Esc: ปล่อยคีย์บอร์ดคืนระบบชั่วคราว
    private void onKbEscape() {
        if (destroyed) return;
        kbEscaped = true;
        updateKbGrab();
        Toast.makeText(this, "⌨️ ปล่อยคีย์บอร์ดคืนระบบแล้ว (เปิดใหม่ได้ที่ตั้งค่าระบบ)",
                Toast.LENGTH_LONG).show();
    }

    // คำนวณว่าตอนนี้ควร grab หรือไม่ แล้วสั่งเมื่อสถานะเปลี่ยน
    private void updateKbGrab() {
        boolean want = kbGrabEnabled && !kbEscaped && !destroyed && overlayBuilt
                && isTargetInForeground && !isTypingMode && !settingsTextEditing;
        if (want == kbGrabRequested) {
            refreshKbGrabButton();
            return;
        }
        kbGrabRequested = want;
        if (!want) {
            kbGrabCount = 0;
            // grab หลุดกลางทางอาจไม่ได้รับ "ปล่อยปุ่ม" ต้องเคลียร์ปุ่มที่ค้าง
            releaseAllTapHolds();
            if (dPadController != null) dPadController.release();
            stopAllRapidTaps();
        }
        ShellServerClient.requestKbGrab(want);
        refreshKbGrabButton();
    }

    private String kbGrabButtonText() {
        if (!kbGrabEnabled) return "⌨️ จับคีย์บอร์ด: ปิด (แตะเพื่อเปิด)";
        if (kbEscaped) return "⌨️ ปล่อยชั่วคราว (แตะเพื่อจับอีกครั้ง)";
        if (!ShellServerClient.isConnected()) return "⌨️ จับคีย์บอร์ด: เปิด (รอ activate.sh)";
        if (kbGrabCount > 0) return "⌨️ จับคีย์บอร์ด: เปิด (จับอยู่ " + kbGrabCount + " ตัว)";
        return "⌨️ จับคีย์บอร์ด: เปิด (ไม่พบคีย์บอร์ด)";
    }

    private void refreshKbGrabButton() {
        if (btnKbGrabRef != null) btnKbGrabRef.setText(kbGrabButtonText());
    }

    private void onKbGrabButtonClick() {
        if (kbGrabEnabled && kbEscaped) {
            kbEscaped = false;
        } else {
            kbGrabEnabled = !kbGrabEnabled;
            kbEscaped = false;
            try {
                getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                        .putBoolean("kb_grab_enabled", kbGrabEnabled).apply();
            } catch (Exception ignored) {}
        }
        updateKbGrab();
        refreshKbGrabButton();
    }

    // ===== [FPS] เปิด/ปิดการแสดง FPS ลอยหน้าจอ =====

    private String fpsToggleButtonText() {
        return "📊 แสดง FPS: " + (fpsOverlay != null && fpsOverlay.isShowing() ? "เปิด" : "ปิด");
    }

    private void refreshFpsToggleButton() {
        if (btnFpsToggleRef != null) btnFpsToggleRef.setText(fpsToggleButtonText());
    }

    private void onFpsToggleButtonClick() {
        if (fpsOverlay == null) return;
        if (fpsOverlay.isShowing()) {
            stopFpsFeature();
        } else {
            startFpsFeature();
        }
        refreshFpsToggleButton();
    }

    private void startFpsFeature() {
        if (fpsOverlay == null) return;
        fpsOverlay.show();
        if (realFpsMonitor != null) realFpsMonitor.stop();
        realFpsMonitor = new RealFpsMonitor(targetPackage, handler,
                (fps, source) -> { if (fpsOverlay != null) fpsOverlay.updateValue(fps, source); });
        realFpsMonitor.start();
        try {
            getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                    .putBoolean("fps_overlay_enabled", true).apply();
        } catch (Exception ignored) {}
    }

    private void stopFpsFeature() {
        if (realFpsMonitor != null) { realFpsMonitor.stop(); realFpsMonitor = null; }
        if (fpsOverlay != null) fpsOverlay.hide();
        try {
            getSharedPreferences("MynovoCore", MODE_PRIVATE).edit()
                    .putBoolean("fps_overlay_enabled", false).apply();
        } catch (Exception ignored) {}
    }

    private void updateEditModeUI() {
        if (destroyed) return;
        boolean editMode = isEditModeActive();

        if (editMode) { stopAllRapidTaps(); releaseAllTapHolds(); }

        if (editModeBackgroundView != null) {
            int wantBgVis = editMode ? View.VISIBLE : View.GONE;
            if (editModeBackgroundView.getVisibility() != wantBgVis) {
                editModeBackgroundView.setVisibility(wantBgVis);
            }
        }

        setListeningForKey(editMode);

        for (View container : mappingButtons) {
            if (container == null) continue;
            container.setAlpha(editMode ? 1.0f : globalBtnAlpha);

            if ("MOUSE_LOCK".equals(String.valueOf(container.getTag()))) {
                TextView mtv = buttonTextViewMap.get(container);
                int circleRes = getDrawableId("ic_circle", 0);
                int eyeRes = getDrawableId("btn_fps_eye", 0);
                if (mtv != null) {
                    if (editMode) {
                        if (circleRes != 0) mtv.setBackgroundResource(circleRes);
                        else mtv.setBackground(makeButtonBg());
                        if (!(isWaitingForKey && pendingMappingButton == container)) {
                            restoreLabel(container);
                        }
                    } else if (eyeRes != 0) {
                        mtv.setBackgroundResource(eyeRes);
                        mtv.setText("");
                    }
                }
            }

            if ("HIDE_MOUSE".equals(String.valueOf(container.getTag()))) {
                TextView htv = buttonTextViewMap.get(container);
                if (htv != null) {
                    if (editMode) {
                        if (!(isWaitingForKey && pendingMappingButton == container)) {
                            restoreLabel(container);
                        }
                    } else {
                        htv.setText("");
                    }
                }
            }

            View closeBtn = container.findViewWithTag("BTN_CLOSE");
            int wantVis = editMode ? View.VISIBLE : View.GONE;
            if (closeBtn != null && closeBtn.getVisibility() != wantVis) {
                closeBtn.setVisibility(wantVis);
            }

            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) container.getLayoutParams();
            if (lp == null) continue;

            boolean touchableNow = (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
            if (touchableNow == editMode) continue;

            if (editMode) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(container, lp);
            } catch (Exception ignored) {}
        }

        applyButtonsHiddenState();
    }

    private void setTypingMode(boolean typing) {
        if (destroyed || mouseController == null || isTypingMode == typing) return;
        if (typing) {
            if (isWaitingForKey) cancelKeyWait();
            if (dPadController != null) dPadController.release();
            stopAllRapidTaps();
            releaseAllTapHolds();
            TouchSimulator.releaseTouch();
            mouseLeftToken = 0;
            closeSubWindows();
            if (menuView != null) menuView.setVisibility(View.GONE);
            isMenuShown = false;
            if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
            updateEditModeUI();
            isTypingMode = true;
            mouseController.setTypingMode(true);
            isMouseMode = false;
            if (floatingView instanceof ImageView) {
                ((ImageView) floatingView).setColorFilter(
                        Color.parseColor("#9950FA7B"), PorterDuff.Mode.SRC_ATOP);
            }
            Toast.makeText(this, "⌨️ โหมดพิมพ์: แตะไอคอนเพื่อกลับ", Toast.LENGTH_SHORT).show();
            updateKbGrab(); // [KB] โหมดพิมพ์ = ปล่อยคีย์บอร์ดคืนระบบ
        } else {
            isTypingMode = false;
            mouseController.setTypingMode(false);
            isMouseMode = mouseController.isMouseActive();
            if (floatingView instanceof ImageView) ((ImageView) floatingView).clearColorFilter();
            updateKbGrab(); // [KB]
        }
    }

    private void createFloatingIcon() {
        try {
            ImageView imgOverlayIcon = new ImageView(this);
            imgOverlayIcon.setImageResource(getDrawableId("btn_b", R.drawable.btn_b));
            imgOverlayIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgOverlayIcon.setAlpha(currentAlpha);

            GradientDrawable circleDrawable = new GradientDrawable();
            circleDrawable.setShape(GradientDrawable.OVAL);
            circleDrawable.setColor(Color.parseColor("#161925"));
            imgOverlayIcon.setBackground(circleDrawable);
            imgOverlayIcon.setClipToOutline(true);

            int iconSize = dpToPx(42);

            floatingParams = new WindowManager.LayoutParams(
                    iconSize, iconSize, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );

            floatingParams.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                floatingParams.alpha = 0.8f;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                floatingParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            floatingParams.x = dpToPx(140);

            final int halfY = -(iconSize / 2);
            final int fullY = dpToPx(0);
            floatingParams.y = halfY;

            floatingView = imgOverlayIcon;
            floatingView.setVisibility(View.VISIBLE);

            floatingView.setOnTouchListener(new View.OnTouchListener() {
                private int initialX, initialY;
                private float initialTouchX, initialTouchY;
                private boolean isMoved = false;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = floatingParams.x;
                            initialY = floatingParams.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            isMoved = false;
                            floatingParams.y = fullY;
                            windowManager.updateViewLayout(floatingView, floatingParams);
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            int deltaX = (int) (event.getRawX() - initialTouchX);
                            int deltaY = (int) (event.getRawY() - initialTouchY);
                            if (Math.abs(deltaX) > dpToPx(5) || Math.abs(deltaY) > dpToPx(5)) {
                                isMoved = true;
                                floatingParams.x = initialX + deltaX;
                                floatingParams.y = fullY + deltaY;
                                windowManager.updateViewLayout(floatingView, floatingParams);
                            }
                            return true;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (!isMoved) {
                                if (isTypingMode) {
                                    setTypingMode(false);
                                } else {
                                    if (isWaitingForKey) cancelKeyWait();
                                    long clickTime = System.currentTimeMillis();
                                    if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
                                        if (menuView != null) menuView.setVisibility(View.GONE);
                                        if (addMenuView != null) showAddMenu();
                                        if (floatingView != null) floatingView.setVisibility(View.GONE);
                                        updateEditModeUI();
                                        isMenuShown = false;
                                    } else {
                                        toggleMenu();
                                    }
                                    lastClickTime = clickTime;
                                }
                            }
                            floatingParams.y = halfY;
                            windowManager.updateViewLayout(floatingView, floatingParams);
                            return true;
                    }
                    return false;
                }
            });

            if (windowManager != null) windowManager.addView(floatingView, floatingParams);
        } catch (Exception e) {}
    }

    private void createMenuWindow() {
        try {
            LinearLayout menuLayout = new LinearLayout(this) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    if (handleKey(event)) return true;
                    return super.dispatchKeyEvent(event);
                }
            };
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));

            GradientDrawable menuBg = new GradientDrawable();
            menuBg.setColor(Color.parseColor("#DD161925"));
            menuBg.setCornerRadius(dpToPx(12));
            menuLayout.setBackground(menuBg);

            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);

            rowLayout.addView(createMenuImageButton(getDrawableId("btn_add", R.drawable.btn_b), v -> {
                if (isWaitingForKey) cancelKeyWait();
                menuView.setVisibility(View.GONE);
                showAddMenu();
                if (floatingView != null) floatingView.setVisibility(View.GONE);
                updateEditModeUI();
            }));

            rowLayout.addView(createMenuImageButton(getDrawableId("btn_config", R.drawable.btn_b), v -> {
                if (isWaitingForKey) cancelKeyWait();
                menuView.setVisibility(View.GONE);
                iconSettingsView.setVisibility(View.VISIBLE);
                if (floatingView != null) floatingView.setVisibility(View.GONE);
                updateEditModeUI();
            }));

            rowLayout.addView(createMenuImageButton(getDrawableId("app_icon_b", R.drawable.btn_b), v -> {
                if (isWaitingForKey) cancelKeyWait();
                closeSubWindows();
                menuView.setVisibility(View.GONE);
                isMenuShown = false;
                if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
                updateEditModeUI();
            }));

            rowLayout.addView(createMenuImageButton(
                    getDrawableId("btn_keyboard", R.drawable.btn_b),
                    v -> setTypingMode(true)));

            rowLayout.addView(createMenuImageButton(getDrawableId("btn_cloud", R.drawable.btn_b), v -> {
                if (isWaitingForKey) cancelKeyWait();
                menuView.setVisibility(View.GONE);
                cloudView.setVisibility(View.VISIBLE);
                if (floatingView != null) floatingView.setVisibility(View.GONE);
                updateEditModeUI();
            }));

            menuLayout.addView(rowLayout);

            menuParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            );
            menuParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            menuParams.y = 0;

            menuView = menuLayout;
            menuView.setVisibility(View.GONE);
            if (windowManager != null) windowManager.addView(menuView, menuParams);
        } catch (Exception e) {}
    }

    private View createMenuImageButton(int imageResId, View.OnClickListener listener) {
        ImageView imgBtn = new ImageView(this);
        imgBtn.setImageResource(imageResId);
        imgBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imgBtn.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dpToPx(54), dpToPx(54));
        params.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        imgBtn.setLayoutParams(params);
        imgBtn.setOnClickListener(listener);
        return imgBtn;
    }

    private boolean isViewTouched(View view, float x, float y) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + view.getWidth() &&
                y >= loc[1] && y <= loc[1] + view.getHeight();
    }

    private boolean isButtonHit(View container, float x, float y) {
        if (container == null || container.getVisibility() != View.VISIBLE) return false;
        View close = container.findViewWithTag("BTN_CLOSE");
        if (close != null && isViewTouched(close, x, y)) return true;
        TextView tv = buttonTextViewMap.get(container);
        View inner = tv != null ? tv : container;
        int[] loc = new int[2];
        inner.getLocationOnScreen(loc);
        float r = Math.min(inner.getWidth(), inner.getHeight()) / 2f;
        float dx = x - (loc[0] + inner.getWidth() / 2f);
        float dy = y - (loc[1] + inner.getHeight() / 2f);
        return dx * dx + dy * dy <= r * r;
    }

    private long targetUiDownTime = 0;
    private boolean swallowMouseUp = false;

    private View findMouseLeftButton() {
        for (Map.Entry<View, Integer> e : buttonKeyMap.entrySet()) {
            Integer v = e.getValue();
            View b = e.getKey();
            if (v == null || b == null || idKeyCode(v) != 60001) continue;
            String tag = String.valueOf(b.getTag());
            if ("HIDE_MOUSE".equals(tag) || "MOUSE_LOCK".equals(tag) || "DPAD".equals(tag)) continue;
            return b;
        }
        return null;
    }

    private void assignMouseLeftToPending() {
        if (pendingMappingButton == null) return;
        final int id = makeKeyId(60001, 0);
        for (Map.Entry<View, Integer> entry : buttonKeyMap.entrySet()) {
            if (entry.getKey() != pendingMappingButton && entry.getValue() != null
                    && entry.getValue() == id) {
                Toast.makeText(this, "ไม่สามารถตั้งปุ่มซ้ำได้!", Toast.LENGTH_SHORT).show();
                cancelKeyWait();
                return;
            }
        }
        finishAssign(id, "");
    }

    private boolean dispatchTouchToUi(float x, float y, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            targetUiView = null;
            targetUiDownTime = android.os.SystemClock.uptimeMillis();
            if (isViewTouched(warningDialogView, x, y)) targetUiView = warningDialogView;
            else if (isViewTouched(floatingView, x, y)) targetUiView = floatingView;
            else if (isViewTouched(menuView, x, y)) targetUiView = menuView;
            else if (isViewTouched(addMenuView, x, y)) targetUiView = addMenuView;
            else if (isViewTouched(iconSettingsView, x, y)) targetUiView = iconSettingsView;
            else if (isViewTouched(cloudView, x, y)) targetUiView = cloudView;
            else if (isViewTouched(buttonSettingsView, x, y)) targetUiView = buttonSettingsView;
            else if (isEditModeActive()) {
                for (int i = mappingButtons.size() - 1; i >= 0; i--) {
                    View btn = mappingButtons.get(i);
                    if (isButtonHit(btn, x, y)) {
                        targetUiView = btn;
                        break;
                    }
                }
                if (targetUiView == null && !isWaitingForKey
                        && isViewTouched(editModeBackgroundView, x, y)) {
                    targetUiView = editModeBackgroundView;
                }
            }

            boolean quickSecondTap = targetUiView != null
                    && targetUiView == pendingMappingButton
                    && (android.os.SystemClock.uptimeMillis() - keyWaitStartTime) < DOUBLE_CLICK_TIME_DELTA;

            if (isWaitingForKey && pendingMappingButton != null
                    && !quickSecondTap
                    && (targetUiView == null || targetUiView == pendingMappingButton)) {
                targetUiView = null;
                assignMouseLeftToPending();
                swallowMouseUp = true;
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP && swallowMouseUp && targetUiView == null) {
            swallowMouseUp = false;
            return true;
        }

        if (targetUiView != null) {
            int[] loc = new int[2];
            targetUiView.getLocationOnScreen(loc);
            float localX = x - loc[0];
            float localY = y - loc[1];

            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(targetUiDownTime != 0 ? targetUiDownTime : now, now, action, x, y, 0);
            event.setLocation(localX, localY);
            targetUiView.dispatchTouchEvent(event);
            event.recycle();

            if (action == MotionEvent.ACTION_UP) {
                targetUiView = null;
            }
            return true;
        }
        return false;
    }

    private void restoreLabel(View c) {
        TextView tv = buttonTextViewMap.get(c);
        if (tv == null) return;
        if ("FIRE".equals(String.valueOf(c.getTag())) && getDrawableId("btn_fire", 0) != 0) {
            tv.setText("");
            return;
        }
        Integer id = buttonKeyMap.get(c);
        if (id != null) { tv.setText(labelOfId(id)); return; }
        String type = c.getTag() != null ? c.getTag().toString() : "NORMAL";
        int dpadResId = getDrawableId("ic_dpad_blue", 0);
        if (type.equals("DPAD") && dpadResId != 0) {
            tv.setText("");
        } else {
            tv.setText(type.equals("DPAD") ? "🕹️" : type.equals("HIDE_MOUSE") ? "👁️" : type.equals("RAPID_FIRE") ? "⚡" : type.equals("MOUSE_LOCK") ? "🖱️" : type.equals("FIRE") ? "Fire" : "?");
        }
    }

    private void setListeningForKey(boolean listening) {
        try {
            if (menuView != null) {
                boolean currentlyFocusable = (menuParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
                if (listening == currentlyFocusable) {
                    if (listening) menuView.requestFocus();
                    return;
                }
                if (listening) {
                    menuParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    menuParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
                } else {
                    menuParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                }
                windowManager.updateViewLayout(menuView, menuParams);
                if (listening) menuView.requestFocus();
            }
        } catch (Exception e) {}
    }

    private void startKeyWait(final View container) {
        if ("FIRE".equals(container.getTag())) {
            Toast.makeText(this, "ปุ่มยิงถูกล็อกไว้เป็นคลิกซ้ายอัตโนมัติแล้ว", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isWaitingToggleKey) cancelToggleKeyWait();

        if (isWaitingForKey && pendingMappingButton != null && pendingMappingButton != container) {
            cancelKeyWait();
        }

        if (cancelWaitingRunnable != null) handler.removeCallbacks(cancelWaitingRunnable);
        isWaitingForKey = true;
        keyWaitStartTime = android.os.SystemClock.uptimeMillis();
        pendingMappingButton = container;
        TextView tv = buttonTextViewMap.get(container);
        if (tv != null) tv.setText("...");

        if (menuView != null) menuView.requestFocus();

        cancelWaitingRunnable = () -> {
            if (isWaitingForKey && pendingMappingButton == container) cancelKeyWait();
        };
        handler.postDelayed(cancelWaitingRunnable, 10000);
    }

    private void cancelKeyWait() {
        if (cancelWaitingRunnable != null) handler.removeCallbacks(cancelWaitingRunnable);
        View c = pendingMappingButton;
        isWaitingForKey = false;
        pendingMappingButton = null;
        if (c != null) restoreLabel(c);
    }

    private String keyLabelOf(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1);
        }

        switch (keyCode) {
            case 60001: return "MS_L";
            case 60002: return "MS_R";
            case 60003: return "MS_MID";
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_ESCAPE: return "ESC";
        }

        String l = KeyEvent.keyCodeToString(keyCode);
        if (l.startsWith("KEYCODE_")) l = l.substring(8);
        return l;
    }

    private int makeKeyId(int keyCode, int scanCode) { return (scanCode << 16) | (keyCode & 0xFFFF); }
    private int idKeyCode(int id) { return id & 0xFFFF; }
    private int idScan(int id) { return id >>> 16; }

    private static final android.util.SparseArray<String> SCAN_LABELS = new android.util.SparseArray<>();
    static {
        String[] base = {
            "", "ESC", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "BKSP", "TAB",
            "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]", "ENTER", "L_CTRL",
            "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "`", "L_SHIFT", "\\",
            "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "R_SHIFT", "NUM*", "L_ALT", "SPACE", "CAPS",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10",
            "NUMLK", "SCRLK", "NUM7", "NUM8", "NUM9", "NUM-", "NUM4", "NUM5", "NUM6", "NUM+",
            "NUM1", "NUM2", "NUM3", "NUM0", "NUM."
        };
        for (int i = 1; i < base.length; i++) SCAN_LABELS.put(i, base[i]);
        SCAN_LABELS.put(86, "<>");
        SCAN_LABELS.put(87, "F11");
        SCAN_LABELS.put(88, "F12");
        SCAN_LABELS.put(96, "NUM_ENT");
        SCAN_LABELS.put(97, "R_CTRL");
        SCAN_LABELS.put(98, "NUM/");
        SCAN_LABELS.put(99, "PRTSC");
        SCAN_LABELS.put(100, "R_ALT");
        SCAN_LABELS.put(102, "HOME");
        SCAN_LABELS.put(103, "UP");
        SCAN_LABELS.put(104, "PGUP");
        SCAN_LABELS.put(105, "LEFT");
        SCAN_LABELS.put(106, "RIGHT");
        SCAN_LABELS.put(107, "END");
        SCAN_LABELS.put(108, "DOWN");
        SCAN_LABELS.put(109, "PGDN");
        SCAN_LABELS.put(110, "INS");
        SCAN_LABELS.put(111, "DEL");
        SCAN_LABELS.put(119, "PAUSE");
        SCAN_LABELS.put(125, "L_WIN");
        SCAN_LABELS.put(126, "R_WIN");
        SCAN_LABELS.put(127, "MENU");
        for (int i = 0; i < 12; i++) SCAN_LABELS.put(183 + i, "F" + (13 + i));
    }

    private String labelOfId(int id) {
        int sc = idScan(id);
        int kc = idKeyCode(id);
        String l = (sc != 0) ? SCAN_LABELS.get(sc) : null;
        if (l != null) return l;
        if (kc == KeyEvent.KEYCODE_UNKNOWN && sc != 0) return "SC" + sc;
        return keyLabelOf(kc);
    }

    private boolean keyMatches(int id, KeyEvent e) {
        int sc1 = idScan(id);
        int sc2 = e.getScanCode();
        int kc1 = idKeyCode(id);
        int kc2 = e.getKeyCode();

        if (sc1 == 0 && sc2 == 0 && kc1 == KeyEvent.KEYCODE_UNKNOWN && kc2 == KeyEvent.KEYCODE_UNKNOWN) {
            return false;
        }

        if (sc1 != 0 && sc2 != 0) return sc1 == sc2;
        return kc1 == kc2;
    }

    private int dpadKeyOf(KeyEvent e) {
        int sc = e.getScanCode();
        if (sc != 0) {
            switch (sc) {
                case 17: return KeyEvent.KEYCODE_W;
                case 30: return KeyEvent.KEYCODE_A;
                case 31: return KeyEvent.KEYCODE_S;
                case 32: return KeyEvent.KEYCODE_D;
                default: return -1;
            }
        }
        int kc = e.getKeyCode();
        if (kc == KeyEvent.KEYCODE_W || kc == KeyEvent.KEYCODE_A
                || kc == KeyEvent.KEYCODE_S || kc == KeyEvent.KEYCODE_D) return kc;
        return -1;
    }

    private boolean isKeyMapped(KeyEvent e) {
        for (Integer v : buttonKeyMap.values()) {
            if (v != null && keyMatches(v, e)) return true;
        }
        return false;
    }

    private void finishAssign(int id, String extra) {
        if (cancelWaitingRunnable != null) handler.removeCallbacks(cancelWaitingRunnable);
        String keyLabel = labelOfId(id);
        TextView tv = buttonTextViewMap.get(pendingMappingButton);
        if (tv != null) tv.setText(keyLabel);
        buttonKeyMap.put(pendingMappingButton, id);
        isWaitingForKey = false;
        pendingMappingButton = null;
        updateEditModeUI();
        savePresetToCloud();
    }

    private static final int MAX_BUTTONS_PER_KEY = 4;

    private boolean isUniqueOnlyKey(int keyCode, int scanCode) {
        if (keyCode == KeyEvent.KEYCODE_SPACE || scanCode == 57) return true;
        if (keyCode == 60001 || keyCode == 60002 || keyCode == 60003) return true;
        if (scanCode >= 183 && scanCode <= 194) return true;
        return false;
    }

    private boolean isKeyAllowedForButton(View btn, KeyEvent ev) {
        if (toggleBtnsKeyId != -1 && keyMatches(toggleBtnsKeyId, ev)) {
            Toast.makeText(this, "ปุ่มนี้ใช้เป็นปุ่มลัดเปิด/ปิดปุ่มอยู่", Toast.LENGTH_SHORT).show();
            return false;
        }

        boolean isWASD = dpadKeyOf(ev) != -1;
        String btnType = btn.getTag() != null ? btn.getTag().toString() : "NORMAL";

        if (!"DPAD".equals(btnType) && isWASD) {
            Toast.makeText(this, "ตั้งซ้ำไม่ได้! (W, A, S, D ล็อคไว้สำหรับ D-Pad)", Toast.LENGTH_SHORT).show();
            return false;
        }

        int limit = isUniqueOnlyKey(ev.getKeyCode(), ev.getScanCode()) ? 1 : MAX_BUTTONS_PER_KEY;
        int used = 0;
        for (Map.Entry<View, Integer> entry : buttonKeyMap.entrySet()) {
            if (entry.getKey() != btn && entry.getValue() != null && keyMatches(entry.getValue(), ev)) {
                used++;
            }
        }
        if (used >= limit) {
            Toast.makeText(this, limit == 1
                    ? "ปุ่มนี้ตั้งซ้ำไม่ได้!"
                    : "ตั้งปุ่มนี้ซ้ำได้สูงสุด " + MAX_BUTTONS_PER_KEY + " ปุ่มแล้ว",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void assignKeyToPending(KeyEvent e) {
        if (!isWaitingForKey || pendingMappingButton == null) return;
        int kc = e.getKeyCode();

        if (!isKeyAllowedForButton(pendingMappingButton, e)) {
            cancelKeyWait();
            return;
        }

        int sc = e.getScanCode();
        int id = makeKeyId(kc, sc);
        finishAssign(id, "");
    }

    public boolean handleKey(KeyEvent event) {
        if (destroyed) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isWaitingToggleKey) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) cancelToggleKeyWait();
                return true;
            }
            if (isWaitingForKey) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) cancelKeyWait();
                return true;
            }
            return false;
        }

        boolean isDown = (event.getAction() == KeyEvent.ACTION_DOWN);
        boolean isUp = (event.getAction() == KeyEvent.ACTION_UP);

        // กำลังรอรับปุ่มสำหรับ "ปุ่มลัดเปิด/ปิดปุ่ม"
        if (isWaitingToggleKey) {
            if (isDown && event.getRepeatCount() == 0) assignToggleKey(event);
            return true;
        }

        // กดปุ่มลัด -> เปิด/ปิดการแสดงปุ่มทั้งหมด
        if (toggleBtnsKeyId != -1 && keyMatches(toggleBtnsKeyId, event)) {
            if (isDown && event.getRepeatCount() == 0 && !isEditModeActive()
                    && isTargetInForeground && !tapDebounced(0x7000 + event.getKeyCode())) {
                toggleButtonsHidden();
            }
            return true;
        }

        int dpadKey = dpadKeyOf(event);
        if (dpadKey != -1) {
            if (!isEditModeActive()) {
                if (dPadController != null) {
                    // [แก้ lag] ไม่ส่ง key-repeat เข้า D-Pad ซ้ำ ๆ (สถานะกดค้างถูกตั้งไว้แล้วตอน down)
                    if (isUp || (isDown && event.getRepeatCount() == 0)) {
                        dPadController.onKeyEvent(dpadKey, isDown);
                    }
                    return true;
                }
            }
        }

        if (isUp) {
            boolean h = false;
            for (Map.Entry<View, Integer> en : buttonKeyMap.entrySet()) {
                Integer v = en.getValue();
                if (v != null && keyMatches(v, event)
                        && "RAPID_FIRE".equals(String.valueOf(en.getKey().getTag()))) {
                    rapidRelease(en.getKey());
                    h = true;
                }
            }
            for (Map.Entry<View, Integer> en : buttonKeyMap.entrySet()) {
                Integer v = en.getValue();
                if (v != null && keyMatches(v, event)) {
                    Integer hp = tapHeldPtr.remove(en.getKey());
                    if (hp != null) { TouchSimulator.simulateRelease(hp); h = true; }
                }
            }
            if (h) return true;
        }

        if (!isDown) return isWaitingForKey;

        if (isWaitingForKey && pendingMappingButton != null) {
            assignKeyToPending(event);
            return true;
        }

        if (event.getRepeatCount() > 0) return isKeyMapped(event);

        boolean handled = false;
        for (Map.Entry<View, Integer> entry : new ArrayList<>(buttonKeyMap.entrySet())) {
            Integer v = entry.getValue();
            if (v != null && keyMatches(v, event)) {
                View b = entry.getKey();
                String tag = String.valueOf(b.getTag());

                if ("HIDE_MOUSE".equals(tag)) {
                    if (!isEditModeActive()) toggleViewModeFromKey();
                    handled = true;
                    continue;
                }

                if ("MOUSE_LOCK".equals(tag)) {
                    if (!isEditModeActive()) toggleMouseLock();
                    handled = true;
                    continue;
                }

                if ("RAPID_FIRE".equals(tag)) {
                    if (!isEditModeActive() && isTargetInForeground) rapidPress(b);
                    handled = true;
                    continue;
                }

                if ("NORMAL".equals(tag)) {
                    Integer cur = tapHeldPtr.get(b);
                    if (cur == null || !TouchSimulator.isHeld(cur)) {
                        int[] l = new int[2];
                        b.getLocationOnScreen(l);
                        int hptr = allocTapPtr();
                        tapHeldPtr.put(b, hptr);
                        TouchSimulator.simulatePress(hptr, l[0] + b.getWidth() / 2, l[1] + b.getHeight() / 2);
                    }
                    handled = true;
                    continue;
                }

                int baseKey = event.getScanCode() != 0 ? 1000 + event.getScanCode() : keyCode;
                if (tapDebounced(baseKey * 31 + System.identityHashCode(b))) { handled = true; continue; }

                int[] loc = new int[2];
                b.getLocationOnScreen(loc);
                int ptr = TAP_PTR_BASE + (tapPtrCounter++ % TAP_PTR_POOL);
                TouchSimulator.simulateClick(ptr, loc[0] + b.getWidth() / 2, loc[1] + b.getHeight() / 2);
                handled = true;
            }
        }
        return handled;
    }

    private boolean hasViewKeyAssigned() {
        for (Map.Entry<View, Integer> e : buttonKeyMap.entrySet()) {
            View b = e.getKey();
            if (e.getValue() != null && b != null) {
                String tag = String.valueOf(b.getTag());
                if ("HIDE_MOUSE".equals(tag) || "MOUSE_LOCK".equals(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void toggleViewModeFromKey() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastViewToggleTime < 50) return;
        lastViewToggleTime = now;
        toggleMouseLock();
    }

    private boolean tapDebounced(int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        Long last = lastTapAt.get(keyCode);
        if (last != null && now - last < 150) return true;
        lastTapAt.put(keyCode, now);
        return false;
    }

    private void applyViewSettingsFromButton() {
        if (mouseController == null) return;
        for (View b : mappingButtons) {
            if (b == null) continue;
            String tag = String.valueOf(b.getTag());
            if ("HIDE_MOUSE".equals(tag)) {
                int[] loc = new int[2];
                b.getLocationOnScreen(loc);
                mouseController.setTargetButtonPosition(
                        loc[0] + b.getWidth() / 2f,
                        loc[1] + b.getHeight() / 2f);
                Float sx = buttonSensXMap.get(b);
                Float sy = buttonSensYMap.get(b);
                mouseController.setViewSensitivity(sx != null ? sx : 1f, sy != null ? sy : 1f);
                Integer hz = buttonHzMap.get(b);
                mouseController.setViewHz(hz != null ? hz : 500);
                float[] fc = flickCfg(b);
                mouseController.setViewFlickAccel(fc[0] > 0.5f);
                mouseController.setLockMaxV(fc[1]);
                mouseController.setBitParams(fc[2], fc[3], fc[4]);
                return;
            }
        }
    }

    private TextView createStepButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(22f);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)));
        return t;
    }

    private void addSensRow(LinearLayout layout, final View container, final boolean isX) {
        final float MIN = 0.01f, MAX = 10.0f;
        final Map<View, Float> map = isX ? buttonSensXMap : buttonSensYMap;
        final String name = isX ? "ความเร็วแกน X: " : "ความเร็วแกน Y: ";
        Float saved = map.get(container);
        float cur = saved != null ? saved : 1.0f;
        cur = Math.max(MIN, Math.min(MAX, cur));

        final TextView tv = createSettingsLabel(name + String.format(java.util.Locale.US, "%.2f", cur));
        layout.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(Math.round((MAX - MIN) * 100f));
        bar.setProgress(Math.round((cur - MIN) * 100f));
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float v = MIN + progress / 100f;
                tv.setText(name + String.format(java.util.Locale.US, "%.2f", v));
                map.put(container, v);
                applyViewSettingsFromButton();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { savePresetToCloud(); }
        });

        TextView minus = createStepButton("−");
        minus.setOnClickListener(v -> {
            bar.setProgress(Math.max(0, bar.getProgress() - 1));
            savePresetToCloud();
        });
        TextView plus = createStepButton("+");
        plus.setOnClickListener(v -> {
            bar.setProgress(Math.min(bar.getMax(), bar.getProgress() + 1));
            savePresetToCloud();
        });

        row.addView(minus);
        row.addView(bar);
        row.addView(plus);
        layout.addView(row);
    }

    private void addHzRow(LinearLayout layout, final View container) {
        final int[] STEPS = {125, 250, 500, 1000};
        Integer saved = buttonHzMap.get(container);
        int curIdx = 2;
        for (int i = 0; i < STEPS.length; i++) {
            if (saved != null && STEPS[i] == saved) curIdx = i;
        }
        final String name = "ความถี่ส่ง (Hz): ";

        final TextView tv = createSettingsLabel(name + STEPS[curIdx]);
        layout.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(STEPS.length - 1);
        bar.setProgress(curIdx);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int hz = STEPS[progress];
                tv.setText(name + hz);
                buttonHzMap.put(container, hz);
                applyViewSettingsFromButton();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { savePresetToCloud(); }
        });

        TextView minus = createStepButton("−");
        minus.setOnClickListener(v -> {
            bar.setProgress(Math.max(0, bar.getProgress() - 1));
            savePresetToCloud();
        });
        TextView plus = createStepButton("+");
        plus.setOnClickListener(v -> {
            bar.setProgress(Math.min(bar.getMax(), bar.getProgress() + 1));
            savePresetToCloud();
        });

        row.addView(minus);
        row.addView(bar);
        row.addView(plus);
        layout.addView(row);
    }

    private interface FloatCb { void on(float v); }

    private String fmtNum(float v) {
        return String.format(java.util.Locale.US, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    private void styleModeButton(Button b, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(selected ? "#8BE9FD" : "#2E3447"));
        g.setCornerRadius(dpToPx(8));
        b.setBackground(g);
        b.setTextColor(selected ? Color.parseColor("#1B1E2E") : Color.WHITE);
    }

    private void addFlickSettings(LinearLayout layout, final View container) {
        final float[] cfg = flickCfg(container);
        layout.addView(createSettingsLabel("โหมดตอนสบัดเมาส์"));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        final Button btnConst = createSubMenuOption("คงที่", "#2E3447", null);
        final Button btnBit = createSubMenuOption("บิต", "#2E3447", null);
        modeRow.addView(btnConst);
        modeRow.addView(btnBit);
        layout.addView(modeRow);

        final LinearLayout bitGroup = new LinearLayout(this);
        bitGroup.setOrientation(LinearLayout.VERTICAL);

        addNumberRow(bitGroup, "เริ่มเร่งเมื่อเร็วเกิน (px/ms)", 0.05f, 20f, cfg[2],
                v -> { cfg[2] = v; applyViewSettingsFromButton(); });
        addNumberRow(bitGroup, "แรงเร่ง (0 = ไม่เร่งเพิ่ม)", 0f, 20f, cfg[3],
                v -> { cfg[3] = v; applyViewSettingsFromButton(); });
        addNumberRow(bitGroup, "เร่งสูงสุด (เท่า)", 1f, 50f, cfg[4],
                v -> { cfg[4] = v; applyViewSettingsFromButton(); });
        layout.addView(bitGroup);

        final Runnable refresh = () -> {
            boolean bit = cfg[0] > 0.5f;
            styleModeButton(btnConst, !bit);
            styleModeButton(btnBit, bit);
            bitGroup.setVisibility(bit ? View.VISIBLE : View.GONE);
        };
        btnConst.setOnClickListener(v -> { cfg[0] = 0f; refresh.run(); applyViewSettingsFromButton(); savePresetToCloud(); });
        btnBit.setOnClickListener(v -> { cfg[0] = 1f; refresh.run(); applyViewSettingsFromButton(); savePresetToCloud(); });
        refresh.run();
    }

    private void addNumberRow(LinearLayout parent, String name, final float min, final float max,
                              float cur, final FloatCb cb) {
        parent.addView(createSettingsLabel(name + "  [" + fmtNum(min) + " - " + fmtNum(max) + "]"));

        final EditText et = new EditText(this);
        et.setText(fmtNum(cur));
        et.setTextColor(Color.WHITE);
        et.setTextSize(14f);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#151823"));
        bg.setCornerRadius(dpToPx(8));
        bg.setStroke(dpToPx(1), Color.parseColor("#44496B"));
        et.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(140),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dpToPx(10);
        et.setLayoutParams(lp);

        final Runnable commit = () -> {
            try {
                float v = Float.parseFloat(et.getText().toString().trim());
                cb.on(Math.max(min, Math.min(max, v)));
            } catch (NumberFormatException ignored) {}
        };
        final Runnable finish = () -> {
            commit.run();
            try {
                float v = Float.parseFloat(et.getText().toString().trim());
                et.setText(fmtNum(Math.max(min, Math.min(max, v))));
            } catch (NumberFormatException ignored) {}
            savePresetToCloud();
        };

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { commit.run(); }
        });
        et.setOnEditorActionListener((tv, actionId, ev) -> {
            finish.run();
            et.clearFocus();
            setSettingsFocusable(false);
            return true;
        });
        et.setOnFocusChangeListener((v, has) -> { if (!has) finish.run(); });
        et.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                setSettingsFocusable(true);
                et.post(et::requestFocus);
            }
            return false;
        });
        parent.addView(et);
    }

    private void setSettingsFocusable(boolean on) {
        // [KB] ตอนพิมพ์ตัวเลขในช่องตั้งค่า ต้องปล่อยคีย์บอร์ดคืนระบบ ไม่งั้นพิมพ์ไม่ได้
        settingsTextEditing = on;
        updateKbGrab();
        try {
            if (buttonSettingsView == null || buttonSettingsParams == null
                    || buttonSettingsView.getParent() == null) return;
            if (on) {
                buttonSettingsParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                buttonSettingsParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
            } else {
                buttonSettingsParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            windowManager.updateViewLayout(buttonSettingsView, buttonSettingsParams);
        } catch (Exception ignored) {}
    }

    private interface IntCb { void on(int v); }

    private void addIntRow(LinearLayout layout, final String name, final int min, final int max,
                           int cur, final IntCb cb) {
        cur = Math.max(min, Math.min(max, cur));
        final TextView tv = createSettingsLabel(name + cur);
        layout.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress;
                tv.setText(name + v);
                cb.on(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { savePresetToCloud(); }
        });

        TextView minus = createStepButton("−");
        minus.setOnClickListener(v -> bar.setProgress(Math.max(0, bar.getProgress() - 1)));
        TextView plus = createStepButton("+");
        plus.setOnClickListener(v -> bar.setProgress(Math.min(bar.getMax(), bar.getProgress() + 1)));

        row.addView(minus);
        row.addView(bar);
        row.addView(plus);
        layout.addView(row);
    }

    private void bringCursorToFront() {
        if (batchLoading) return;
        if (mouseController != null) mouseController.bringCursorToFront();
    }

    private void toggleMouseLock() {
        if (isTypingMode) return;
        releaseAllTapHolds();
        if (dPadController != null) dPadController.release();
        stopAllRapidTaps();
        TouchSimulator.releaseTouch();
        mouseLeftToken = 0;
        if (mouseController == null) return;

        boolean willLock = !mouseController.isMouseActive();
        if (willLock) {
            applyViewSettingsFromButton();
            closeSubWindows();
            isMenuShown = false;
            if (menuView != null) menuView.setVisibility(View.GONE);
            if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
            updateEditModeUI();
        }

        mouseController.toggleMouseMode();
        isMouseMode = mouseController.isMouseActive();
    }

    private void createAddMenuWindow() {
        try {
            LinearLayout addMenuLayout = new LinearLayout(this);
            addMenuLayout.setOrientation(LinearLayout.VERTICAL);
            addMenuLayout.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#151823"));
            bg.setCornerRadius(dpToPx(20));
            bg.setStroke(dpToPx(1), Color.parseColor("#2E3447"));
            addMenuLayout.setBackground(bg);

            LinearLayout headerLayout = new LinearLayout(this);
            headerLayout.setOrientation(LinearLayout.HORIZONTAL);
            headerLayout.setGravity(Gravity.CENTER_VERTICAL);
            headerLayout.setPadding(0, 0, 0, dpToPx(4));

            LinearLayout titleContainer = new LinearLayout(this);
            titleContainer.setOrientation(LinearLayout.VERTICAL);
            titleContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView tvTitle = new TextView(this);
            tvTitle.setText("เพิ่มตัวควบคุม");
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(15f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            titleContainer.addView(tvTitle);
            headerLayout.addView(titleContainer);

            TextView btnClose = new TextView(this);
            btnClose.setText("✕");
            btnClose.setTextColor(Color.parseColor("#8C92A4"));
            btnClose.setTextSize(16f);
            btnClose.setGravity(Gravity.CENTER);
            btnClose.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
            btnClose.setOnClickListener(v -> {
                if (isWaitingForKey) cancelKeyWait();
                addMenuView.setVisibility(View.GONE);
                menuView.setVisibility(View.GONE);
                menuView.setVisibility(View.VISIBLE);
                updateEditModeUI();
            });
            headerLayout.addView(btnClose);
            addMenuLayout.addView(headerLayout);

            int idTap = getDrawableId("btn_tap", R.drawable.btn_b);
            int idDpad = getDrawableId("btn_dpad", R.drawable.btn_b);
            int idFps = getDrawableId("btn_fps", R.drawable.btn_b);
            int idRapid = getDrawableId("btn_rapid", R.drawable.btn_b);
            int idSwipe = getDrawableId("btn_swipe", R.drawable.btn_b);
            int idZoom = getDrawableId("btn_zoom", R.drawable.btn_b);

            int idFire = getDrawableId("btn_fire_box", R.drawable.btn_b);

            int defaultCenterX = (int)(maxScreenWidth / 2f) - dpToPx(80);
            int defaultCenterY = (int)(maxScreenHeight / 2f) - dpToPx(80);

            android.widget.ScrollView sv = makeCappedScrollView(dpToPx(90));
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            sv.addView(rows, new android.widget.ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

            rows.addView(createButtonRow(
                "แตะ", idTap, v -> addNewMappingButton("NORMAL", defaultCenterX, defaultCenterY, false, -1, true),
                "จอยสติ๊ก", idDpad, v -> {
                    if (dPadController != null) return;
                    addNewMappingButton("DPAD", defaultCenterX, defaultCenterY, false, -1, true);
                }
            ));

            rows.addView(createButtonRow(
                "มุมมอง", idFps, v -> addNewMappingButton("HIDE_MOUSE", defaultCenterX, defaultCenterY, false, -1, true),
                "แตะซ้ำ", idRapid, v -> addNewMappingButton("RAPID_FIRE", defaultCenterX, defaultCenterY, false, -1, true)
            ));

            rows.addView(createButtonRow(
                "ปัด", idSwipe, v -> addNewMappingButton("SWIPE", defaultCenterX, defaultCenterY, false, -1, true),
                "ยิง", idFire, v -> {
                    int fireKeyId = makeKeyId(60001, 0);
                    addNewMappingButton("FIRE", defaultCenterX, defaultCenterY, false, fireKeyId, true);
                }
            ));

            addMenuLayout.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            addMenuView = addMenuLayout;
            addMenuParams = new WindowManager.LayoutParams(
                    dpToPx(280), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            addMenuParams.gravity = Gravity.TOP | Gravity.START;
            addMenuParams.x = 0; addMenuParams.y = 0;
            addMenuView.setVisibility(View.GONE);
            windowManager.addView(addMenuView, addMenuParams);
        } catch (Exception e) {}
    }

    private void showAddMenu() {
        if (addMenuView == null) return;
        try {
            if (addMenuView.getParent() != null) windowManager.removeView(addMenuView);
            windowManager.addView(addMenuView, addMenuParams);
        } catch (Exception ignored) {}
        addMenuView.setVisibility(View.VISIBLE);
        bringCursorToFront();
    }

    private LinearLayout createButtonRow(String text1, int iconRes1, View.OnClickListener l1, String text2, int iconRes2, View.OnClickListener l2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dpToPx(3), 0, dpToPx(3));
        row.setLayoutParams(rowParams);
        row.addView(createSingleMenuCard(text1, iconRes1, l1));
        if (text2 == null) {
            View spacer = new View(this);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dpToPx(124), 1.0f);
            sp.setMargins(dpToPx(3), 0, dpToPx(3), 0);
            spacer.setLayoutParams(sp);
            row.addView(spacer);
        } else {
            row.addView(createSingleMenuCard(text2, iconRes2, l2));
        }
        return row;
    }

    private View createSingleMenuCard(String title, int iconResId, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1B1F2D"));
        cardBg.setCornerRadius(dpToPx(10));
        cardBg.setStroke(dpToPx(1), Color.parseColor("#2E3447"));
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, dpToPx(124), 1.0f);
        cardParams.setMargins(dpToPx(3), 0, dpToPx(3), 0);
        card.setLayoutParams(cardParams);

        ImageView ivIcon = new ImageView(this);
        ivIcon.setImageResource(iconResId);
        ivIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ivIcon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(84), dpToPx(84)));
        card.addView(ivIcon);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(title);
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setTextSize(11f);
        tvLabel.setGravity(Gravity.CENTER);
        tvLabel.setPadding(0, dpToPx(3), 0, 0);
        card.addView(tvLabel);

        card.setOnClickListener(listener);
        return card;
    }

    private void updateDpadButtonsSize() {
        for (View container : mappingButtons) {
            if (container != null && "DPAD".equals(container.getTag())) {
                int mainSize = dpadSize;
                int padding = dpToPx(10);
                int containerSize = mainSize + (padding * 2);

                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) container.getLayoutParams();
                if (lp != null) {
                    lp.width = containerSize;
                    lp.height = containerSize;
                    try {
                        windowManager.updateViewLayout(container, lp);
                    } catch (Exception ignored) {}
                }

                if (container instanceof FrameLayout) {
                    FrameLayout fl = (FrameLayout) container;
                    for (int i = 0; i < fl.getChildCount(); i++) {
                        View child = fl.getChildAt(i);
                        if (child instanceof TextView || "DPAD_IMG".equals(child.getTag())) {
                            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) child.getLayoutParams();
                            flp.width = mainSize;
                            flp.height = mainSize;
                            child.setLayoutParams(flp);
                        }
                    }
                }
            }
        }
    }

    private void createIconSettingsWindow() {
        try {
            final LinearLayout outer = new LinearLayout(this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#282B3E"));
            bg.setCornerRadius(dpToPx(16));
            bg.setStroke(dpToPx(2), Color.parseColor("#44496B"));
            outer.setBackground(bg);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("ตั้งค่าระบบ (Settings)");
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(18f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            tvTitle.setPadding(0, 0, 0, dpToPx(20));
            outer.addView(tvTitle);

            android.widget.ScrollView sv = makeCappedScrollView(dpToPx(200));
            final LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            sv.addView(layout, new android.widget.ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
            outer.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            layout.addView(createSettingsLabel("ความโปร่งใสของไอคอนเมนู"));
            SeekBar alphaSeekBar = new SeekBar(this);
            alphaSeekBar.setMax(100);
            alphaSeekBar.setProgress((int)(currentAlpha * 100));
            alphaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    currentAlpha = Math.max(0.3f, progress / 100f);
                    if (floatingView != null) floatingView.setAlpha(currentAlpha);
                    saveSettings();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(alphaSeekBar);

            final TextView tvDpadLabel = createSettingsLabel("ขนาดปุ่มจอยสติ๊ก (DPAD Size): " + dpadSizeDp + " dp");
            layout.addView(tvDpadLabel);

            SeekBar dpadSizeSeekBar = new SeekBar(this);
            dpadSizeSeekBar.setMax(200);
            dpadSizeSeekBar.setProgress(Math.max(0, dpadSizeDp - 80));
            dpadSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    dpadSizeDp = 80 + progress;
                    dpadSize = dpToPx(dpadSizeDp);
                    tvDpadLabel.setText("ขนาดปุ่มจอยสติ๊ก (DPAD Size): " + dpadSizeDp + " dp");
                    updateDpadButtonsSize();
                    saveSettings();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(dpadSizeSeekBar);

            final TextView tvBtnSize = createSettingsLabel("ขนาดปุ่มทุกปุ่ม (Button Size): " + globalBtnSizeDp + " dp");
            layout.addView(tvBtnSize);
            SeekBar btnSizeBar = new SeekBar(this);
            btnSizeBar.setMax(96 - 20);
            btnSizeBar.setProgress(Math.max(0, Math.min(96 - 20, globalBtnSizeDp - 20)));
            btnSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int dp = 20 + progress;
                    tvBtnSize.setText("ขนาดปุ่มทุกปุ่ม (Button Size): " + dp + " dp");
                    applyGlobalButtonSize(dp);
                    saveSettings();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) { savePresetToCloud(); }
            });
            layout.addView(btnSizeBar);

            final int minPct = Math.round(BTN_MIN_ALPHA * 100f);
            int curBtnPct = Math.max(minPct, Math.min(100, Math.round(globalBtnAlpha * 100f)));
            final TextView tvBtnAlpha = createSettingsLabel("ความโปร่งใสปุ่มทุกปุ่ม (Button Opacity): " + curBtnPct + "%");
            layout.addView(tvBtnAlpha);
            SeekBar btnAlphaBar = new SeekBar(this);
            btnAlphaBar.setMax(100 - minPct);
            btnAlphaBar.setProgress(curBtnPct - minPct);
            btnAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int pct = minPct + progress;
                    tvBtnAlpha.setText("ความโปร่งใสปุ่มทุกปุ่ม (Button Opacity): " + pct + "%");
                    applyGlobalButtonAlpha(pct / 100f);
                    saveSettings();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(btnAlphaBar);

            layout.addView(createSettingsLabel("ขนาดเมาส์จำลอง (Pointer Size)"));
            SeekBar sizeSeekBar = new SeekBar(this);
            sizeSeekBar.setMax(100);
            int currentSizeDp = Math.round(cursorSize / (float) dpToPx(1));
            int sizeProgress = (currentSizeDp - 20) * 2;
            sizeSeekBar.setProgress(Math.max(0, Math.min(100, sizeProgress)));

            sizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    cursorSize = dpToPx(20 + (progress / 2));
                    if (mouseController != null) mouseController.updateCursorSize(cursorSize);
                    saveSettings();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(sizeSeekBar);

            TextView tvSpeedLabel = createSettingsLabel("ความเร็วเมาส์ (Sensitivity): " + String.format("%.1f", mouseSensitivity) + "x");
            layout.addView(tvSpeedLabel);

            SeekBar speedSeekBar = new SeekBar(this);
            speedSeekBar.setMax(100);
            speedSeekBar.setProgress((int) (mouseSensitivity * 10));
            speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mouseSensitivity = Math.max(0.5f, progress / 10f);
                    tvSpeedLabel.setText("ความเร็วเมาส์ (Sensitivity): " + String.format("%.1f", mouseSensitivity) + "x");
                    if (mouseController != null) mouseController.updateSensitivity(mouseSensitivity);
                    saveSettings();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            layout.addView(speedSeekBar);

            // ปุ่มลัดเปิด/ปิดการแสดงปุ่ม
            layout.addView(createSettingsLabel("ปุ่มลัดเปิด/ปิดการแสดงปุ่ม"));
            LinearLayout toggleRow = new LinearLayout(this);
            toggleRow.setOrientation(LinearLayout.HORIZONTAL);
            toggleRow.setGravity(Gravity.CENTER_VERTICAL);

            btnToggleKeyRef = createSubMenuOption(toggleKeyButtonText(), "#2E3447",
                    v -> startToggleKeyWait());
            btnToggleKeyRef.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(44), 3f));

            Button btnClearKey = createSubMenuOption("ล้าง", "#FF5555",
                    v -> { cancelToggleKeyWait(); setToggleKey(-1); });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dpToPx(44), 1f);
            clp.leftMargin = dpToPx(8);
            btnClearKey.setLayoutParams(clp);

            toggleRow.addView(btnToggleKeyRef);
            toggleRow.addView(btnClearKey);
            layout.addView(toggleRow);

            // [FPS] เปิด/ปิดการแสดง FPS ลอยหน้าจอ (แทนที่ปุ่มจับคีย์บอร์ดแบบ exclusive เดิม)
            layout.addView(createSettingsLabel("แสดง FPS"));
            btnFpsToggleRef = createSubMenuOption(fpsToggleButtonText(), "#2E3447",
                    v -> onFpsToggleButtonClick());
            btnFpsToggleRef.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)));
            layout.addView(btnFpsToggleRef);

            // เลือก FPS (ล็อกรีเฟรชเรตหน้าจอ) 60 / 90 / 120
            layout.addView(createSettingsLabel("เลือก FPS (ล็อกรีเฟรชเรตหน้าจอ)"));
            LinearLayout rateRow = new LinearLayout(this);
            rateRow.setOrientation(LinearLayout.HORIZONTAL);
            rateRow.setGravity(Gravity.CENTER);
            for (int i = 0; i < 3; i++) {
                final int hz = RATE_STEPS[i];
                btnRateOpt[i] = createSubMenuOption(hz + "", "#2E3447", v -> onRateButtonClick(hz));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dpToPx(44), 1f);
                rp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
                btnRateOpt[i].setLayoutParams(rp);
                rateRow.addView(btnRateOpt[i]);
            }
            layout.addView(rateRow);
            refreshRateButtons();

            Button btnClose = new Button(this);
            btnClose.setText("CLOSE");
            btnClose.setTextColor(Color.parseColor("#282B3E"));
            btnClose.setBackgroundColor(Color.WHITE);
            btnClose.setTextSize(12f);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(dpToPx(100), dpToPx(40));
            btnParams.gravity = Gravity.END;
            btnParams.topMargin = dpToPx(30);
            btnClose.setLayoutParams(btnParams);
            btnClose.setOnClickListener(v -> {
                if (isWaitingToggleKey) cancelToggleKeyWait();
                if (isWaitingForKey) cancelKeyWait();
                iconSettingsView.setVisibility(View.GONE);
                menuView.setVisibility(View.VISIBLE);
                updateEditModeUI();
            });
            outer.addView(btnClose);

            configParams = new WindowManager.LayoutParams(
                    dpToPx(400), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            configParams.gravity = Gravity.CENTER;

            iconSettingsView = outer;
            iconSettingsView.setVisibility(View.GONE);
            windowManager.addView(iconSettingsView, configParams);
        } catch (Exception e) {}
    }

    @SuppressWarnings("deprecation")
    private int[] realScreenSize() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{dm.widthPixels, dm.heightPixels};
            }
        } catch (Exception ignored) {}
        return new int[]{(int) maxScreenWidth, (int) maxScreenHeight};
    }

    private void resetAllMappingButtons() {
        if (destroyed) return;
        if (isWaitingForKey) cancelKeyWait();
        closeButtonSettings();

        if (dPadController != null) dPadController.release();
        dPadController = null;
        stopAllRapidTaps();
        TouchSimulator.releaseTouch();
        mouseLeftToken = 0;

        for (Runnable r : rapidFireRunnables.values()) handler.removeCallbacks(r);
        rapidFireRunnables.clear();
        activeTouchPointers.clear();

        for (View btn : new ArrayList<>(mappingButtons)) {
            try { windowManager.removeView(btn); } catch (Exception ignored) {}
        }
        mappingButtons.clear();
        buttonKeyMap.clear();
        buttonTextViewMap.clear();
        buttonSizeDpMap.clear();
        buttonAlphaMap.clear();
        buttonSensXMap.clear();
        buttonSensYMap.clear();
        buttonHzMap.clear();
        buttonFlickMap.clear();
        clearAllRapidData();

        savePresetToCloud();
        updateEditModeUI();
    }

    private TextView createSettingsLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#A0A5C0"));
        tv.setTextSize(13f);
        tv.setPadding(dpToPx(10), dpToPx(15), 0, dpToPx(8));
        return tv;
    }

    private void createCloudWindow() {
        try {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#151823"));
            bg.setCornerRadius(dpToPx(24));
            bg.setStroke(dpToPx(1), Color.parseColor("#2E3447"));
            layout.setBackground(bg);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("☁️ Cloud Preset");
            tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
            tvTitle.setTextSize(16f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            tvTitle.setPadding(0, 0, 0, dpToPx(16));
            layout.addView(tvTitle);

            LinearLayout cloudActionLayout = new LinearLayout(this);
            cloudActionLayout.setOrientation(LinearLayout.HORIZONTAL);
            cloudActionLayout.setGravity(Gravity.CENTER);

            cloudActionLayout.addView(createSubMenuOption("💾 บันทึก", "#50FA7B", v -> {
                savePresetToCloud();
                Toast.makeText(this, "✅ บันทึกพรีเซ็ตสำเร็จ!", Toast.LENGTH_SHORT).show();
                closeSubWindows();
                updateEditModeUI();
            }));

            cloudActionLayout.addView(createSubMenuOption("📂 โหลด", "#BD93F9", v -> {
                loadPresetFromCloud();
                Toast.makeText(this, "📂 โหลดพรีเซ็ตสำเร็จ!", Toast.LENGTH_SHORT).show();
                closeSubWindows();
                updateEditModeUI();
            }));

            layout.addView(cloudActionLayout);

            LinearLayout resetActionLayout = new LinearLayout(this);
            resetActionLayout.setOrientation(LinearLayout.HORIZONTAL);
            resetActionLayout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams resetRowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            resetRowParams.topMargin = dpToPx(12);
            resetActionLayout.setLayoutParams(resetRowParams);

            resetActionLayout.addView(createSubMenuOption("🗑️ รีเซ็ตปุ่ม", "#FF5555", v -> {
                showWarningDialog(
                    "🗑️ รีเซ็ตปุ่มทั้งหมด",
                    "ปุ่มทั้งหมดบนหน้าจอของเกมนี้จะถูกลบ และล้างพรีเซ็ตที่บันทึกไว้",
                    () -> {
                        resetAllMappingButtons();
                        Toast.makeText(this, "🗑️ รีเซ็ตปุ่มเรียบร้อย!", Toast.LENGTH_SHORT).show();
                        if (cloudView != null) cloudView.setVisibility(View.GONE);
                        if (menuView != null) menuView.setVisibility(View.VISIBLE);
                        updateEditModeUI();
                    }
                );
            }));
            layout.addView(resetActionLayout);

            TextView tvBack = new TextView(this);
            tvBack.setText("➖ ปิด");
            tvBack.setTextColor(Color.parseColor("#FF5555"));
            tvBack.setTextSize(14f);
            tvBack.setGravity(Gravity.CENTER);
            tvBack.setPadding(0, dpToPx(12), 0, 0);
            tvBack.setOnClickListener(v -> {
                if (isWaitingForKey) cancelKeyWait();
                cloudView.setVisibility(View.GONE);
                menuView.setVisibility(View.VISIBLE);
                updateEditModeUI();
            });
            layout.addView(tvBack);

            cloudParams = new WindowManager.LayoutParams(
                    dpToPx(340), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            cloudParams.gravity = Gravity.TOP | Gravity.START;
            cloudParams.x = dpToPx(15); cloudParams.y = dpToPx(80);

            cloudView = layout;
            cloudView.setVisibility(View.GONE);
            windowManager.addView(cloudView, cloudParams);
        } catch (Exception e) {}
    }

    private Button createSubMenuOption(String text, String colorHex, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14f);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dpToPx(8));
        btn.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dpToPx(50), 1.0f);
        params.setMargins(dpToPx(8), 0, dpToPx(8), 0);
        btn.setLayoutParams(params);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void closeSubWindows() {
        closeButtonSettings();
        if (addMenuView != null) addMenuView.setVisibility(View.GONE);
        if (iconSettingsView != null) iconSettingsView.setVisibility(View.GONE);
        if (cloudView != null) cloudView.setVisibility(View.GONE);
        isMenuShown = false;
    }

    private void toggleMenu() {
        if (isMenuShown) {
            menuView.setVisibility(View.GONE);
            closeSubWindows();
            isMenuShown = false;
            if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
        } else {
            menuView.setVisibility(View.VISIBLE);
            isMenuShown = true;
            if (floatingView != null) floatingView.setVisibility(View.GONE);
        }
        updateEditModeUI();
    }

    private void addNewMappingButton(String type, int posX, int posY, boolean askKey, int savedKey, boolean useStagger) {
        if (destroyed) return;
        try {
            final FrameLayout mapBtnContainer = new FrameLayout(this);
            mapBtnContainer.setTag(type);

            final int mainSize = type.equals("DPAD") ? dpadSize : dpToPx(globalBtnSizeDp);
            int subSize = dpToPx(20);
            int padding = dpToPx(10);
            final int containerSize = mainSize + (padding * 2);

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (!isEditModeActive()) {
                flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }

            final WindowManager.LayoutParams btnParams = new WindowManager.LayoutParams(
                    containerSize, containerSize, overlayType(), flags, PixelFormat.TRANSLUCENT
            );
            btnParams.gravity = Gravity.TOP | Gravity.START;
            btnParams.alpha = BTN_WINDOW_ALPHA;
            mapBtnContainer.setAlpha(globalBtnAlpha);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                btnParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            int actualX = posX;
            int actualY = posY;
            if (useStagger) {
                int staggerOffset = mappingButtons.size() * dpToPx(45);
                actualX += staggerOffset;
                actualY += (staggerOffset % dpToPx(200));
                int[] scr = realScreenSize();
                int rangeX = Math.max(1, scr[0] - containerSize);
                int rangeY = Math.max(1, scr[1] - containerSize);
                actualX = Math.max(0, actualX % rangeX);
                actualY = Math.max(0, actualY % rangeY);
            }
            btnParams.x = actualX;
            btnParams.y = actualY;

            if (type.equals("DPAD")) {
                ImageView dpadImgView = new ImageView(this);
                int dpadResId = getDrawableId("ic_dpad_blue", 0);
                if (dpadResId != 0) {
                    dpadImgView.setImageResource(dpadResId);
                } else {
                    dpadImgView.setBackground(makeButtonBg());
                }
                dpadImgView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(mainSize, mainSize);
                imgParams.gravity = Gravity.CENTER;
                dpadImgView.setLayoutParams(imgParams);
                dpadImgView.setTag("DPAD_IMG");
                mapBtnContainer.addView(dpadImgView);

                dPadController = new DPadController(mapBtnContainer, handler, new DPadController.Host() {
                    @Override public int getDpadSize() { return dpadSize; }
                    @Override public boolean isDestroyed() { return destroyed; }
                });
            }

            TextView mapBtn = new TextView(this);
            mapBtn.setTextColor(Color.WHITE);
            mapBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            mapBtn.setGravity(Gravity.CENTER);
            mapBtn.setTextSize(12f);
            mapBtn.setMaxLines(1);
            mapBtn.setPadding(dpToPx(1), 0, dpToPx(1), 0);
            mapBtn.setShadowLayer(4f, 0f, 0f, Color.BLACK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mapBtn.setAutoSizeTextTypeUniformWithConfiguration(8, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            }

            if (type.equals("DPAD")) {
                mapBtn.setText("");
            } else {
                String label;
                if (type.equals("HIDE_MOUSE")) {
                    int fpsEyeRes = getDrawableId("btn_fps_eye", 0);
                    if (fpsEyeRes != 0) {
                        mapBtn.setBackgroundResource(fpsEyeRes);
                        label = "";
                    } else {
                        label = "👁️";
                        mapBtn.setBackground(makeButtonBg());
                    }
                }
                else if (type.equals("RAPID_FIRE")) {
                    label = "⚡";
                    mapBtn.setBackground(makeButtonBg());
                }
                else if (type.equals("MOUSE_LOCK")) {
                    label = "🖱️";
                    mapBtn.setBackground(makeButtonBg());
                }
                else if (type.equals("FIRE")) {
                    int fireRes = getDrawableId("btn_fire", 0);
                    if (fireRes != 0) {
                        mapBtn.setBackgroundResource(fireRes);
                        label = "";
                    } else {
                        label = "Fire";
                        mapBtn.setBackground(makeButtonBg());
                    }
                }
                else if (type.equals("NORMAL")) {
                    int tapRes = getDrawableId("btn_circle_outline", 0);
                    if (tapRes != 0) {
                        mapBtn.setBackgroundResource(tapRes);
                        label = "";
                    } else {
                        label = "?";
                        mapBtn.setBackground(makeButtonBg());
                    }
                }
                else {
                    label = "?";
                    mapBtn.setBackground(makeButtonBg());
                }
                mapBtn.setText(label);
            }

            FrameLayout.LayoutParams mainParams = new FrameLayout.LayoutParams(mainSize, mainSize);
            mainParams.gravity = Gravity.CENTER;
            mapBtn.setLayoutParams(mainParams);
            mapBtnContainer.addView(mapBtn);

            buttonTextViewMap.put(mapBtnContainer, mapBtn);

            ImageView btnClose = new ImageView(this);
            btnClose.setImageResource(getDrawableId("ic_close", android.R.drawable.ic_menu_close_clear_cancel));
            btnClose.setScaleType(ImageView.ScaleType.FIT_CENTER);
            btnClose.setTag("BTN_CLOSE");
            btnClose.setVisibility(isEditModeActive() ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(subSize, subSize);
            closeParams.gravity = Gravity.TOP | Gravity.END;
            btnClose.setLayoutParams(closeParams);
            btnClose.setClickable(true); btnClose.setFocusable(true);
            btnClose.setOnClickListener(v -> {
                try {
                    if (pendingMappingButton == mapBtnContainer) cancelKeyWait();
                    if ("DPAD".equals(mapBtnContainer.getTag())) {
                        if (dPadController != null) dPadController.release();
                        dPadController = null;
                    }
                    removeRapidData(mapBtnContainer);
                    windowManager.removeView(mapBtnContainer);
                    mappingButtons.remove(mapBtnContainer);
                    buttonKeyMap.remove(mapBtnContainer);
                    buttonTextViewMap.remove(mapBtnContainer);
                    buttonSizeDpMap.remove(mapBtnContainer);
                    buttonAlphaMap.remove(mapBtnContainer);
                    buttonSensXMap.remove(mapBtnContainer);
                    buttonSensYMap.remove(mapBtnContainer);
                    buttonHzMap.remove(mapBtnContainer);
                    buttonFlickMap.remove(mapBtnContainer);
                    savePresetToCloud();
                } catch (Exception ignored) {}
            });
            mapBtnContainer.addView(btnClose);

            mapBtn.setOnTouchListener(new View.OnTouchListener() {
                private int initialX, initialY;
                private float initialTouchX, initialTouchY;
                private boolean isMoved = false;
                private boolean handledAsDouble = false;
                private long lastTapTime = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!isEditModeActive()) return false;

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN: {
                            initialX = btnParams.x; initialY = btnParams.y;
                            initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                            isMoved = false;
                            handledAsDouble = false;

                            long downNow = android.os.SystemClock.uptimeMillis();
                            if (lastTapTime != 0 && downNow - lastTapTime < DOUBLE_CLICK_TIME_DELTA) {
                                lastTapTime = 0;
                                handledAsDouble = true;
                                if (isWaitingForKey) cancelKeyWait();
                                openButtonSettings(mapBtnContainer);
                            }
                            return true;
                        }

                        case MotionEvent.ACTION_MOVE: {
                            if (handledAsDouble) return true;
                            int deltaX = (int) (event.getRawX() - initialTouchX);
                            int deltaY = (int) (event.getRawY() - initialTouchY);
                            if (Math.abs(deltaX) > dpToPx(5) || Math.abs(deltaY) > dpToPx(5)) {
                                isMoved = true;
                                btnParams.x = initialX + deltaX;
                                btnParams.y = initialY + deltaY;
                                windowManager.updateViewLayout(mapBtnContainer, btnParams);
                            }
                            return true;
                        }

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            if (handledAsDouble) { handledAsDouble = false; return true; }
                            if (isMoved) {
                                lastTapTime = 0;
                                savePresetToCloud();
                            } else {
                                lastTapTime = android.os.SystemClock.uptimeMillis();
                                if (!"DPAD".equals(mapBtnContainer.getTag())) startKeyWait(mapBtnContainer);
                            }
                            return true;
                        }
                    }
                    return false;
                }
            });

            windowManager.addView(mapBtnContainer, btnParams);
            bringCursorToFront();
            mappingButtons.add(mapBtnContainer);
            if (!isTargetInForeground) mapBtnContainer.setVisibility(View.GONE);

            if ("FIRE".equals(type) && savedKey == -1) {
                savedKey = makeKeyId(60001, 0);
            }

            if (savedKey != -1) {
                buttonKeyMap.put(mapBtnContainer, savedKey);
                restoreLabel(mapBtnContainer);
            }
            if (!batchLoading) updateEditModeUI();

            if (askKey || useStagger) savePresetToCloud();
        } catch (Exception e) {}
    }

    private void applyButtonSize(View container, int sizeDp) {
        try {
            int main = dpToPx(sizeDp);
            int total = main + dpToPx(10) * 2;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) container.getLayoutParams();
            if (lp != null) {
                lp.x += (lp.width - total) / 2;
                lp.y += (lp.height - total) / 2;
                lp.width = total;
                lp.height = total;
                windowManager.updateViewLayout(container, lp);
            }
            TextView tv = buttonTextViewMap.get(container);
            if (tv != null) {
                android.view.ViewGroup.LayoutParams p = tv.getLayoutParams();
                p.width = main;
                p.height = main;
                tv.setLayoutParams(p);
            }
            buttonSizeDpMap.put(container, sizeDp);
        } catch (Exception ignored) {}
    }

    private void applyGlobalButtonSize(int dp) {
        globalBtnSizeDp = dp;
        for (View b : new ArrayList<>(mappingButtons)) {
            if (b == null || "DPAD".equals(b.getTag())) continue;
            applyButtonSize(b, dp);
        }
    }

    private void applyButtonAlpha(View container, float a) {
        try {
            container.setAlpha(a);
            buttonAlphaMap.put(container, a);
        } catch (Exception ignored) {}
    }

    private void applyGlobalButtonAlpha(float a) {
        globalBtnAlpha = Math.max(BTN_MIN_ALPHA, Math.min(1.0f, a));
        for (View b : new ArrayList<>(mappingButtons)) {
            if (b == null) continue;
            applyButtonAlpha(b, globalBtnAlpha);
        }
    }

    private android.widget.ScrollView makeCappedScrollView(final int reservePx) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = Math.max(dpToPx(120), realScreenSize()[1] - reservePx);
                super.onMeasure(widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
            }
        };
        sv.setFillViewport(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return sv;
    }

    private String buttonTypeName(String type) {
        if ("DPAD".equals(type)) return "จอยสติ๊ก";
        if ("HIDE_MOUSE".equals(type)) return "มุมมอง";
        if ("RAPID_FIRE".equals(type)) return "แตะซ้ำ";
        if ("MOUSE_LOCK".equals(type)) return "ล็อกเมาส์";
        if ("SWIPE".equals(type)) return "ปัด";
        if ("ZOOM".equals(type)) return "ซูม";
        if ("FIRE".equals(type)) return "ยิง";
        return "แตะ";
    }

    private void closeButtonSettings() {
        try {
            if (buttonSettingsView != null && buttonSettingsView.getParent() != null) {
                windowManager.removeView(buttonSettingsView);
            }
        } catch (Exception ignored) {}
        buttonSettingsView = null;
        // [KB] ปิดหน้าตั้งค่าปุ่มขณะกำลังพิมพ์ตัวเลข -> กลับมา grab ต่อ
        if (settingsTextEditing) {
            settingsTextEditing = false;
            updateKbGrab();
        }
    }

    private void openButtonSettings(final View container) {
        if (destroyed || container == null) return;
        closeButtonSettings();
        try {
            final boolean isDpad = "DPAD".equals(container.getTag());
            final String type = container.getTag() != null ? container.getTag().toString() : "NORMAL";

            final LinearLayout outer = new LinearLayout(this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(dpToPx(24), dpToPx(14), dpToPx(24), dpToPx(20));

            int bgRes = getDrawableId("bg_settings", 0);
            if (bgRes != 0) {
                outer.setBackgroundResource(bgRes);
            } else {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#1B1E2E"));
                bg.setCornerRadius(dpToPx(16));
                bg.setStroke(dpToPx(2), Color.parseColor("#44496B"));
                outer.setBackground(bg);
            }

            TextView tvTitle = new TextView(this);
            tvTitle.setText("ตั้งค่าปุ่ม (Button Settings)");
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(18f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            tvTitle.setPadding(0, 0, 0, dpToPx(4));
            outer.addView(tvTitle);

            Integer keyId = buttonKeyMap.get(container);
            String keyText = isDpad ? "W A S D"
                    : (keyId != null ? labelOfId(keyId) : "ยังไม่ได้กำหนด");
            TextView tvSub = new TextView(this);
            tvSub.setText(buttonTypeName(type) + "  •  ปุ่ม: " + keyText);
            tvSub.setTextColor(Color.parseColor("#8BE9FD"));
            tvSub.setTextSize(13f);
            tvSub.setGravity(Gravity.CENTER);
            tvSub.setPadding(0, 0, 0, dpToPx(6));
            outer.addView(tvSub);

            android.widget.ScrollView sv = makeCappedScrollView(dpToPx(190));
            final LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dpToPx(6), 0, dpToPx(6), 0);
            sv.addView(layout, new android.widget.ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
            outer.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            if ("HIDE_MOUSE".equals(type)) {
                addSensRow(layout, container, true);
                addSensRow(layout, container, false);
                addHzRow(layout, container);
                addFlickSettings(layout, container);
            }

            if ("RAPID_FIRE".equals(type)) {
                layout.addView(createSettingsLabel("โหมดแตะรัว"));
                LinearLayout rapidModeRow = new LinearLayout(this);
                rapidModeRow.setOrientation(LinearLayout.HORIZONTAL);
                rapidModeRow.setGravity(Gravity.CENTER);
                final Button btnRapidHold = createSubMenuOption("กดค้างเพื่อรัว", "#2E3447", null);
                final Button btnRapidToggle = createSubMenuOption("กดเริ่ม/กดหยุด", "#2E3447", null);
                rapidModeRow.addView(btnRapidHold);
                rapidModeRow.addView(btnRapidToggle);
                layout.addView(rapidModeRow);
                final Runnable refreshRapidMode = () -> {
                    boolean tg = isRapidToggle(container);
                    styleModeButton(btnRapidHold, !tg);
                    styleModeButton(btnRapidToggle, tg);
                };
                btnRapidHold.setOnClickListener(v -> {
                    buttonRapidToggleMap.put(container, false);
                    rapidStop(container);
                    refreshRapidMode.run();
                    savePresetToCloud();
                });
                btnRapidToggle.setOnClickListener(v -> {
                    buttonRapidToggleMap.put(container, true);
                    refreshRapidMode.run();
                    savePresetToCloud();
                });
                refreshRapidMode.run();

                Integer r = buttonIntervalMap.get(container);
                addIntRow(layout, "ช่วงห่างระหว่างแตะ Interval (ms): ", 2, 500, r != null ? r : 100,
                        v -> buttonIntervalMap.put(container, v));
                Integer p = buttonPressMsMap.get(container);
                addIntRow(layout, "เวลากดต่อครั้ง Click duration (ms): ", 2, 100, p != null ? p : 25,
                        v -> buttonPressMsMap.put(container, v));
            }

            Button btnClose = new Button(this);
            btnClose.setText("CLOSE");
            btnClose.setTextColor(Color.parseColor("#1B1E2E"));
            btnClose.setBackgroundColor(Color.WHITE);
            btnClose.setTextSize(12f);
            LinearLayout.LayoutParams btnP = new LinearLayout.LayoutParams(dpToPx(100), dpToPx(40));
            btnP.gravity = Gravity.END;
            btnP.topMargin = dpToPx(18);
            btnClose.setLayoutParams(btnP);
            btnClose.setOnClickListener(v -> {
                savePresetToCloud();
                closeButtonSettings();
                updateEditModeUI();
            });
            outer.addView(btnClose);

            buttonSettingsParams = new WindowManager.LayoutParams(
                    dpToPx(360), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            buttonSettingsParams.gravity = Gravity.CENTER;

            buttonSettingsView = outer;
            windowManager.addView(buttonSettingsView, buttonSettingsParams);
            bringCursorToFront();
            updateEditModeUI();
        } catch (Exception e) {}
    }

    private void savePresetToCloud() {
        if (batchLoading) return;
        final String key = presetKey(onExternalDisplay());
        final String json;
        try {
            JSONArray jsonArray = new JSONArray();
            for (View btn : new ArrayList<>(mappingButtons)) {
                if (btn == null) continue;
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) btn.getLayoutParams();
                if (p == null) continue;

                JSONObject obj = new JSONObject();
                obj.put("x", p.x);
                obj.put("y", p.y);
                obj.put("type", btn.getTag() != null ? btn.getTag().toString() : "NORMAL");
                Integer kc = buttonKeyMap.get(btn);
                obj.put("key", kc != null ? kc : -1);
                Integer sz = buttonSizeDpMap.get(btn);
                if (sz != null) obj.put("size", sz);
                Float sxv = buttonSensXMap.get(btn);
                if (sxv != null) obj.put("sx", (double) sxv);
                Float syv = buttonSensYMap.get(btn);
                if (syv != null) obj.put("sy", (double) syv);
                Integer hzv = buttonHzMap.get(btn);
                if (hzv != null) obj.put("hz", (int) hzv);
                float[] fc = buttonFlickMap.get(btn);
                if (fc != null) {
                    obj.put("fm", (int) fc[0]);
                    obj.put("lm", (double) fc[1]);
                    obj.put("br", (double) fc[2]);
                    obj.put("bg", (double) fc[3]);
                    obj.put("bm", (double) fc[4]);
                }
                Integer rr = buttonIntervalMap.get(btn);
                if (rr != null) obj.put("ims", (int) rr);
                Boolean tgv = buttonRapidToggleMap.get(btn);
                if (tgv != null) obj.put("tg", tgv ? 1 : 0);
                Integer pp = buttonPressMsMap.get(btn);
                if (pp != null) obj.put("pms", (int) pp);
                jsonArray.put(obj);
            }
            json = jsonArray.toString();
        } catch (Exception e) {
            return;
        }
        try {
            SharedPreferences prefs = getSharedPreferences(mappingPrefsName(), MODE_PRIVATE);
            prefs.edit()
                    .putString(key, json)
                    .putString(key + "_size", (int) maxScreenWidth + "," + (int) maxScreenHeight)
                    .apply();
        } catch (Exception ignored) {}
    }

    private void loadPresetFromCloud() {
        handler.post(() -> {
            if (destroyed) return;
            batchLoading = true;
            try {
                stopAllRapidTaps();
                for (View btn : mappingButtons) {
                    try { windowManager.removeView(btn); } catch (Exception ignored) {}
                }
                mappingButtons.clear();
                buttonKeyMap.clear();
                buttonTextViewMap.clear();
                buttonSizeDpMap.clear();
                buttonAlphaMap.clear();
                buttonSensXMap.clear();
                buttonSensYMap.clear();
                buttonHzMap.clear();
                buttonFlickMap.clear();
                clearAllRapidData();
                if (dPadController != null) dPadController.release();
                dPadController = null;

                SharedPreferences prefs = getSharedPreferences(mappingPrefsName(), MODE_PRIVATE);
                String jsonStr = prefs.getString(presetKey(onExternalDisplay()), "");

                if (!jsonStr.isEmpty()) {
                    JSONArray jsonArray = new JSONArray(jsonStr);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject obj = jsonArray.getJSONObject(i);
                        int x = obj.getInt("x");
                        int y = obj.getInt("y");
                        String type = obj.has("type") ? obj.getString("type") : "NORMAL";
                        // ข้ามปุ่ม "ซ่อนปุ่ม" ที่ค้างอยู่ในพรีเซ็ตเก่า (ฟังก์ชันนี้ถูกลบออกแล้ว)
                        if ("HIDE_BTNS".equals(type)) continue;
                        int key = obj.has("key") ? obj.getInt("key") : -1;
                        if ("FIRE".equals(type) && key == -1) {
                            key = makeKeyId(60001, 0);
                        }
                        int before = mappingButtons.size();
                        addNewMappingButton(type, x, y, false, key, false);
                        if (mappingButtons.size() > before) {
                            View added = mappingButtons.get(mappingButtons.size() - 1);
                            if (obj.has("sx")) buttonSensXMap.put(added, (float) obj.getDouble("sx"));
                            if (obj.has("sy")) buttonSensYMap.put(added, (float) obj.getDouble("sy"));
                            if (obj.has("hz")) buttonHzMap.put(added, obj.getInt("hz"));
                            if (obj.has("fm")) {
                                buttonFlickMap.put(added, new float[]{
                                        obj.getInt("fm"),
                                        (float) obj.optDouble("lm", 0.8),
                                        (float) obj.optDouble("br", 0.8),
                                        (float) obj.optDouble("bg", 0.0),
                                        (float) obj.optDouble("bm", 4.0)});
                            }
                            if (obj.has("ims")) {
                                buttonIntervalMap.put(added, obj.getInt("ims"));
                            } else if (obj.has("rate")) {
                                // พรีเซ็ตเก่าเก็บเป็นครั้ง/วิ แปลงเป็น ms
                                int oldRate = Math.max(1, obj.getInt("rate"));
                                buttonIntervalMap.put(added, Math.max(2, Math.round(1000f / oldRate)));
                            }
                            if (obj.has("tg")) buttonRapidToggleMap.put(added, obj.getInt("tg") == 1);
                            if (obj.has("pms")) buttonPressMsMap.put(added, obj.getInt("pms"));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                batchLoading = false;
            }
            bringCursorToFront();
            updateEditModeUI();
        });
    }

    private void startAppMonitor() {
        if (isChecking || monitorHandler == null) return;
        isChecking = true;
        monitorHandler.post(checkAppRunnable);
        handler.removeCallbacks(focusGuardRunnable);
        handler.post(focusGuardRunnable);
    }

    private Runnable checkAppRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            try {
                String cur0 = getForegroundAppRobust();
                if (probeActive && !targetPackage.isEmpty() && getPackageName().equals(cur0)) {
                    cur0 = targetPackage;
                }
                final String currentApp = cur0;

                final boolean nowForeground =
                        targetPackage.isEmpty() || targetPackage.equals(currentApp);

                // [แก้ไข] เช็กทุกครั้ง (เดิมเช็กเฉพาะ gameDisplayId >= 0 ทำให้ย้ายกลับมาจอมือถือไม่ได้)
                handler.post(() -> {
                    if (destroyed || targetDisplay == null || pendingDisplaySwitch != null) return;
                    android.view.Display want = findTargetDisplay();
                    if (want != null && want.getDisplayId() != targetDisplay.getDisplayId()) {
                        scheduleDisplayReevaluation();
                    }
                });

                if (!targetPackage.isEmpty()) {
                    if (targetPackage.equals(currentApp)) {
                        targetSeen = true;
                        ownAppCount = 0;
                        awaySince = 0;
                    } else if (targetSeen) {
                        long nowMs = android.os.SystemClock.uptimeMillis();
                        if (awaySince == 0) awaySince = nowMs;
                        boolean inOwnApp = getPackageName().equals(currentApp);
                        ownAppCount = inOwnApp ? ownAppCount + 1 : 0;
                        boolean stopNow = false;
                        if (inOwnApp && ownAppCount >= 2) {
                            stopNow = true;
                        } else if (!inOwnApp && nowMs - awaySince >= AWAY_STOP_MS) {
                            killOnDestroy = true;
                            stopNow = true;
                        }
                        if (stopNow) {
                            isChecking = false;
                            handler.post(() -> { if (!destroyed) stopSelf(); });
                            return;
                        }
                    }
                }

                if (nowForeground != isTargetInForeground) {
                    handler.post(() -> {
                        if (!destroyed) applyForegroundChange(nowForeground);
                    });
                }
            } catch (Exception e) {}
            if (isChecking && !destroyed && monitorHandler != null) monitorHandler.postDelayed(this, 250);
        }
    };

    private void applyForegroundChange(boolean nowForeground) {
        releaseAllTapHolds();
        boolean prevForeground = isTargetInForeground;
        isTargetInForeground = nowForeground;
        blockMetaKey = nowForeground;

        if (!prevForeground && nowForeground) {
            activeTouchPointers.clear();
            TouchSimulator.releaseTouch();
            mouseLeftToken = 0;
            kbEscaped = false; // [KB] กลับเข้าเกมแล้ว จับคีย์บอร์ดอีกครั้ง
        }
        updateKbGrab(); // [KB] ออกจากเกม = ปล่อยคีย์บอร์ดคืนระบบ

        if (floatingView == null) return;

        if (nowForeground) {
            if (!isEditModeActive() && floatingView.getVisibility() != View.VISIBLE) {
                floatingView.setVisibility(View.VISIBLE);
            }
            for (View btn : mappingButtons) btn.setVisibility(View.VISIBLE);
            applyButtonsHiddenState();
            if (mouseController != null) mouseController.resume();
        } else {
            if (dPadController != null) dPadController.release();
            stopAllRapidTaps();
            TouchSimulator.releaseTouch();
            mouseLeftToken = 0;
            for (Runnable r : rapidFireRunnables.values()) handler.removeCallbacks(r);
            rapidFireRunnables.clear();
            activeTouchPointers.clear();

            if (isTypingMode) setTypingMode(false);

            if (mouseController != null) {
                mouseController.pause();
                isMouseMode = false;
            }

            if (menuView != null) menuView.setVisibility(View.GONE);
            closeSubWindows();
            updateEditModeUI();
            for (View btn : mappingButtons) btn.setVisibility(View.GONE);
            floatingView.setVisibility(View.GONE);
        }
    }

    private Runnable focusGuardRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            try {
                if (isTargetInForeground && !isEditModeActive() && mouseController != null) {
                    mouseController.checkAndRegainFocus();
                }
                // [KB] ต้องการ grab อยู่แต่ยังไม่ได้เชื่อม InputServer (เช่นรัน activate.sh ทีหลัง) -> ลองเชื่อมใหม่
                // [แก้ lag] เดิมลองทุก 400ms ตลอดเวลา เปลี่ยนเป็นทุก 3 วินาที
                if (kbGrabRequested && !ShellServerClient.isConnected()) {
                    long nowMs = android.os.SystemClock.uptimeMillis();
                    if (nowMs - lastKbReconnectAt >= 3000) {
                        lastKbReconnectAt = nowMs;
                        ShellServerClient.ensureConnectedAsync();
                        refreshKbGrabButton();
                    }
                }
            } catch (Exception ignored) {}
            if (isChecking && !destroyed) handler.postDelayed(this, 400);
        }
    };

    private java.lang.reflect.Method shizukuNewProcess = null;
    private long lastShizukuCheck = 0;
    private String fgSource = "usage";

    // [แก้ไข] เกมรันอยู่จอไหนจริง -> เป็นตัวตัดสินสุดท้าย (ไม่ล็อกตาม GAME_DISPLAY อีก)
    // ต้องเห็นตรงกัน 2 รอบติดกันก่อนถึงจะสลับจอ เพื่อกันกะพริบ
    private void applyObservedGameDisplay(int observed) {
        lastObservedReal = observed;
        int real = mapDisplay(observed);
        int desired = gameDisplayId > 0 ? gameDisplayId : 0;
        if (real == desired) {
            pendingExtId = -1;
            pendingExtCount = 0;
            return;
        }
        if (pendingExtId == real) pendingExtCount++;
        else { pendingExtId = real; pendingExtCount = 1; }

        if (pendingExtCount >= 2) {
            pendingExtId = -1;
            pendingExtCount = 0;
            if (real > 0) ensureDisplayAccessible(real);
            gameDisplayId = (real > 0) ? real : -1;
            displayConfirmed = true;
        }
    }

    private String getTopAppViaShizuku() {
        try {
            if (!Shizuku.pingBinder()) return null;
            if (shizukuNewProcess == null) {
                shizukuNewProcess = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                shizukuNewProcess.setAccessible(true);
            }
            Process proc = (Process) shizukuNewProcess.invoke(null, new Object[]{
                    new String[]{"sh", "-c", "dumpsys activity activities | grep -E 'Display #|ResumedActivity'"}, null, null});
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("u\\d+\\s+([A-Za-z0-9_.]+)/");
            java.util.regex.Pattern dispPat = java.util.regex.Pattern.compile("Display #(\\d+)");
            String top = null, fallback = null;
            boolean targetResumed = false;
            boolean onDefault = false;
            int otherId = -1;
            int curDisplay = -1;
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher dm2 = dispPat.matcher(line);
                if (dm2.find()) {
                    curDisplay = Integer.parseInt(dm2.group(1));
                    continue;
                }
                java.util.regex.Matcher m = pat.matcher(line);
                if (!m.find()) continue;

                boolean isGlobalLine = line.contains("topResumedActivity") || line.contains("mLastResumed");

                if (!targetPackage.isEmpty() && targetPackage.equals(m.group(1))) {
                    targetResumed = true;
                    if (!isGlobalLine && curDisplay >= 0) {
                        if (curDisplay == android.view.Display.DEFAULT_DISPLAY) onDefault = true;
                        else otherId = curDisplay;
                    }
                }
                if (line.contains("topResumedActivity")) { if (top == null) top = m.group(1); }
                else if (fallback == null) fallback = m.group(1);
            }
            try { reader.close(); } catch (Exception ignored) {}
            try { proc.waitFor(); } catch (Exception ignored) {}

            if (targetResumed) {
                // [แก้ lag] ผลจาก activities ชัดเจนและตรงกับจอปัจจุบันอยู่แล้ว -> ไม่ต้องรัน
                // "dumpsys window displays" ซ้ำ (หนักมาก) ใช้เฉพาะตอนกำกวมหรือจอเปลี่ยน
                int quick = -1;
                if (onDefault && otherId <= 0) quick = 0;
                else if (!onDefault && otherId > 0) quick = otherId;
                int desired = gameDisplayId > 0 ? gameDisplayId : 0;

                int real;
                if (displayConfirmed && quick >= 0 && quick == desired) {
                    real = quick;
                } else {
                    real = queryDisplayViaWindow(targetPackage);
                    if (real < 0) real = quick;
                }
                if (real >= 0) applyObservedGameDisplay(real);
                return targetPackage;
            }
            return top != null ? top : fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    private volatile String shizukuTopApp = null;
    private volatile long shizukuAt = 0;
    private volatile boolean shizukuBusy = false;
    private long usageChangedAt = 0;
    private String prevUsage = "";

    private String getForegroundAppRobust() {
        String usage = getForegroundApp();
        long now = android.os.SystemClock.uptimeMillis();
        if (!usage.equals(prevUsage)) { prevUsage = usage; usageChangedAt = now; }

        // [แก้ lag] เดิมสั่ง dumpsys ผ่าน Shizuku ทุก 1 วินาทีตลอดเวลา (หนักมาก ทำให้เกม/ระบบกระตุก)
        // ตอนนี้: ถ้า UsageStats บอกว่าเกมอยู่หน้าจอนิ่ง ๆ แล้ว ให้เช็กห่างขึ้นเป็น 2.5 วินาที
        // ถ้ามีการเปลี่ยนแอปเมื่อไม่นานนี้ หรือไม่ใช่เกม ให้เช็กถี่ 1 วินาทีเหมือนเดิม
        boolean stableOnTarget = !targetPackage.isEmpty()
                && targetPackage.equals(usage)
                && now - usageChangedAt > 3000
                && displayConfirmed;
        long shizukuInterval = stableOnTarget ? 2500 : 1000;

        if (!shizukuBusy && now - lastShizukuCheck >= shizukuInterval) {
            lastShizukuCheck = now;
            shizukuBusy = true;
            new Thread(() -> {
                try {
                    String r = getTopAppViaShizuku();
                    if (r != null) { shizukuTopApp = r; shizukuAt = android.os.SystemClock.uptimeMillis(); }
                } finally { shizukuBusy = false; }
            }).start();
        }
        String sz = shizukuTopApp;
        // [แก้ lag] ขยายอายุผล Shizuku ให้สอดคล้องกับช่วงเช็กที่ห่างขึ้น
        if (sz != null && shizukuAt > usageChangedAt && now - shizukuAt < 6000) {
            fgSource = "shizuku";
            return sz;
        }
        fgSource = "usage";
        return usage;
    }

    private String lastForegroundApp = "None";
    private long lastUsageQueryTime = 0;
    private final java.util.LinkedHashMap<String, String> openActivities = new java.util.LinkedHashMap<>();

    private String getForegroundApp() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long from = (lastUsageQueryTime == 0) ? now - 1000L * 3600 : lastUsageQueryTime - 1000L;
            UsageEvents usageEvents = usm.queryEvents(from, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                int type = event.getEventType();
                String pkg = event.getPackageName();
                String cls = event.getClassName();
                if (pkg == null) continue;
                String key = pkg + "/" + (cls != null ? cls : "");
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    openActivities.remove(key);
                    openActivities.put(key, pkg);
                } else if (type == 23 || type == 24) {
                    openActivities.remove(key);
                }
            }
            lastUsageQueryTime = now;

            String top = "None";
            for (String p : openActivities.values()) top = p;
            if (!targetPackage.isEmpty() && openActivities.containsValue(targetPackage)) top = targetPackage;
            lastForegroundApp = top;
            return top;
        } catch (Exception e) { return lastForegroundApp; }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        blockMetaKey = false;
        instance = null;
        isChecking = false;
        probeActive = false;

        // [KB] ปล่อยคีย์บอร์ดคืนระบบและเลิกรับเฟรมคีย์
        try {
            kbGrabRequested = false;
            ShellServerClient.setKeyListener(null);
            ShellServerClient.requestKbGrab(false);
        } catch (Throwable ignored) {}

        // [FPS] หยุด monitor และซ่อน overlay
        try {
            if (realFpsMonitor != null) { realFpsMonitor.stop(); realFpsMonitor = null; }
            if (fpsOverlay != null) { fpsOverlay.hide(); fpsOverlay = null; }
        } catch (Throwable ignored) {}

        try {
            final DisplayProbeActivity probe = DisplayProbeActivity.instance;
            if (probe != null) probe.runOnUiThread(probe::finish);
        } catch (Exception ignored) {}

        try {
            if (pendingDisplaySwitch != null) handler.removeCallbacks(pendingDisplaySwitch);
            if (displayListener != null) {
                android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
                if (dm != null) dm.unregisterDisplayListener(displayListener);
            }
        } catch (Exception ignored) {}

        if (killOnDestroy) {
            final int pid = android.os.Process.myPid();
            new Thread(() -> {
                try { TouchSimulator.releaseTouch(); } catch (Exception ignored) {}
                try { restoreNavBlocking(); } catch (Exception ignored) {}
                try { Thread.sleep(150); } catch (Exception ignored) {}
                android.os.Process.killProcess(pid);
            }).start();
        }

        try { handler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try {
            if (monitorHandler != null) monitorHandler.removeCallbacksAndMessages(null);
            if (overlayBuilt) monitorThread.quitSafely();
        } catch (Exception ignored) {}

        try { closeWarningDialog(); } catch (Exception ignored) {}
        try { closeButtonSettings(); } catch (Exception ignored) {}

        try {
            if (mouseController != null) {
                mouseController.destroy();
                mouseController = null;
            }
        } catch (Exception ignored) {}

        try { stopAllRapidTaps(); } catch (Exception ignored) {}

        for (View btn : mappingButtons) { try { windowManager.removeView(btn); } catch (Exception ignored) {} }
        mappingButtons.clear();
        buttonKeyMap.clear();
        buttonTextViewMap.clear();
        buttonHzMap.clear();
        buttonFlickMap.clear();
        try { clearAllRapidData(); } catch (Exception ignored) {}
        rapidFireRunnables.clear();
        activeTouchPointers.clear();
        dPadController = null;
        isMouseMode = false;
        mouseLeftToken = 0;

        try { if (editModeBackgroundView != null) windowManager.removeView(editModeBackgroundView); } catch (Exception ignored) {}
        try { if (floatingView != null) windowManager.removeView(floatingView); } catch (Exception ignored) {}
        try { if (menuView != null) windowManager.removeView(menuView); } catch (Exception ignored) {}
        try { if (addMenuView != null) windowManager.removeView(addMenuView); } catch (Exception ignored) {}
        try { if (iconSettingsView != null) windowManager.removeView(iconSettingsView); } catch (Exception ignored) {}
        try { if (cloudView != null) windowManager.removeView(cloudView); } catch (Exception ignored) {}

        try { if (overlayBuilt) saveSettings(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}

        if (!killOnDestroy) {
            new Thread(() -> {
                try { TouchSimulator.releaseTouch(); } catch (Exception ignored) {}
                try { restoreNavBlocking(); } catch (Exception ignored) {}
            }).start();
        }

        super.onDestroy();
    }
}

              