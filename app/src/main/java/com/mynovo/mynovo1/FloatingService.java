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
    private volatile boolean kbGrabEnabled = true;
    private volatile boolean kbEscaped = false;
    private volatile boolean kbGrabRequested = false;
    private volatile int kbGrabCount = 0;
    private boolean settingsTextEditing = false;
    private Button btnKbGrabRef = null;
    private long kbDownTime = 0;
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
    private int presetSlot = 0;
    private final Button[] btnSlot = new Button[3];
    private EditText etShareCode;
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
    // เก็บเป็น "ช่วงห่าง (ms)"
    private final Map<View, Integer> buttonIntervalMap = new HashMap<>();
    // โหมดแตะรัวของแต่ละปุ่ม: false/ไม่มีค่า = กดค้างเพื่อรัว, true = กดเริ่ม กดอีกครั้งหยุด
    private final Map<View, Boolean> buttonRapidToggleMap = new HashMap<>();
    private final Map<View, Integer> buttonPressMsMap = new HashMap<>();
    // [เพิ่ม] ปุ่มแตะ (NORMAL) ที่เปิดโหมด "แตะรัว" (Tap quickly to trigger)
    private final Map<View, Boolean> buttonQuickTapMap = new HashMap<>();
    private int rapidPtrCounter = 0;
    private View rapidMouseHeld = null;

    private int tapPtrCounter = 0;
    private static final int TAP_PTR_BASE = 40;
    private static final int TAP_PTR_POOL = 20;

    // ปุ่มแตะ (NORMAL) ที่กำลังกดค้างอยู่ -> pointer id ที่ใช้
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
        return targetPackage + (external ? "_preset_dex" : "_preset") + (presetSlot > 0 ? "_s" + presetSlot : "");
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

    // [เพิ่ม] ปุ่มแตะ (NORMAL) ที่เปิดโหมดแตะรัว
    private boolean isQuickTap(View b) {
        if (b == null) return false;
        Boolean q = buttonQuickTapMap.get(b);
        return q != null && q && "NORMAL".equals(String.valueOf(b.getTag()));
    }

    // [เพิ่ม] ปุ่มที่ทำงานแบบแตะรัว (RAPID_FIRE หรือ NORMAL ที่เปิดแตะรัว)
    private boolean isRapidLike(View b) {
        if (b == null) return false;
        return "RAPID_FIRE".equals(String.valueOf(b.getTag())) || isQuickTap(b);
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
        buttonQuickTapMap.remove(b);
    }

    private void clearAllRapidData() {
        for (RapidTap rt : rapidTapMap.values()) rt.release();
        rapidTapMap.clear();
        buttonIntervalMap.clear();
        buttonRapidToggleMap.clear();
        buttonPressMsMap.clear();
        buttonQuickTapMap.clear();
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
            presetSlot = Math.max(0, Math.min(2, cp.getInt("preset_slot", 0)));
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

    // ถ้าจอ DeX ยังเข้าถึงไม่ได้ จะพยายามเปิดจอซ้ำหลายรอบ และเมื่อหมดเวลาจะสร้างบนจอมือถือชั่วคราว
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

    // ไม่เปลี่ยน gameDisplayId เองตอนมีจอใหม่ถูกเพิ่ม
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
        if (isRapidLike(mapped)) { // [แก้ไข] รองรับปุ่มแตะที่เปิดแตะรัว
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

    // คืนค่า "จอที่เกมกำลัง resumed อยู่จริง" เท่านั้น
    //   0  = จอมือถือ, >0 = จอ DeX/จอภายนอก, -1 = ไม่รู้/กำกวม
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

    // วิธีหลัก: dumpsys window displays ระบุจอชัดเจนด้วย "mDisplayId=N" และ mFocusedApp/mCurrentFocus ต่อจอ
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

    // ลำดับการเลือกจอตอนเริ่ม:
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

    // ซ่อนปุ่มได้เฉพาะเมื่อมีการตั้งปุ่มลัดไว้ในตั้งค่าระบบ
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
            // ทิ้งเฟรม key-repeat (value==2) ตั้งแต่ต้นทาง
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
                // [แก้ไข] ครอบคลุมทั้ง RAPID_FIRE และ NORMAL ที่เปิดแตะรัว
                if (v != null && keyMatches(v, event) && isRapidLike(en.getKey())) {
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

                // [เพิ่ม] ปุ่มแตะที่เปิดโหมดแตะรัว
                if (isQuickTap(b)) {
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