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
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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

    // ระบบคำสั่งสำหรับหยุดการทำงานของ Service
    public static final String ACTION_STOP = "com.mynovo.mynovo1.ACTION_STOP_FLOATING";

    private WindowManager windowManager;

    // ระบบจัดการพื้นหลังขณะเข้าโหมดแก้ไขตำแหน่งปุ่ม (Edit Mode)
    private FrameLayout editModeBackgroundView;
    private WindowManager.LayoutParams editBgParams;
    private long lastBgClickTime = 0;

    // ระบบจัดการหน้าต่างไอคอนลอย, เมนูหลัก, เมนูเพิ่มปุ่ม และการตั้งค่า
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

    // ระบบหน้าต่างแจ้งเตือน (Warning Dialog)
    private View warningDialogView = null;
    private WindowManager.LayoutParams warningParams;

    // ระบบตรวจสอบแพ็กเกจเกมเป้าหมายและการทำงานเบื้องหลัง
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

    // ระบบโหมดพิมพ์ (Typing Mode) และการโหลดข้อมูล
    private boolean isTypingMode = false;
    private boolean batchLoading = false;

    // ระบบแมปปิ้งปุ่มควบคุมบนหน้าจอทั้งหมด
    private List<View> mappingButtons = new ArrayList<>();
    private Map<View, Integer> buttonKeyMap = new HashMap<>();
    private Map<View, TextView> buttonTextViewMap = new HashMap<>();
    private Map<View, Integer> buttonSizeDpMap = new HashMap<>();
    private Map<View, Float> buttonAlphaMap = new HashMap<>();
    private Map<View, Float> buttonSensXMap = new HashMap<>();
    private Map<View, Float> buttonSensYMap = new HashMap<>();
    private Map<View, Integer> buttonHzMap = new HashMap<>();
    private Map<View, Long> activeTouchPointers = new HashMap<>();
    private Map<View, Runnable> rapidFireRunnables = new HashMap<>();

    // ระบบกดซ้ำรัวๆ (Rapid Fire)
    private final Map<View, RapidTap> rapidTapMap = new HashMap<>();
    private final Map<View, Integer> buttonRateMap = new HashMap<>();
    private final Map<View, Integer> buttonPressMsMap = new HashMap<>();
    private int rapidPtrCounter = 0;
    private View rapidMouseHeld = null;

    // ระบบควบคุมเมาส์จำลอง (Mouse Controller)
    private MouseController mouseController;
    private boolean isMouseMode = false;

    // ระบบตั้งค่าการแสดงผลและความไว
    private float currentAlpha = 0.9f;
    private float mouseSensitivity = 4.0f;
    private int cursorSize;
    private float maxScreenWidth = 0f;
    private float maxScreenHeight = 0f;

    // ระบบควบคุมจอยสติ๊ก (DPAD) และขนาดปุ่ม
    private int dpadSizeDp = 160;
    private int globalBtnSizeDp = 32;
    private static final float BTN_WINDOW_ALPHA = 0.4f;
    private float globalBtnAlpha = 1.0f;
    private int dpadSize;
    private DPadController dPadController;

    private View targetUiView = null;

    // ระบบกำหนดปุ่มคีย์บอร์ด (Key Mapping)
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

    // ระบบเธรดตรวจสอบแอปพลิเคชัน (Monitor Thread)
    private final HandlerThread monitorThread = new HandlerThread("AppMonitor");
    private Handler monitorHandler;

    // ระบบแปลงหน่วย DP เป็น Pixel
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    // ระบบดึงข้อมูล Drawable Resource จากชื่อ
    private int getDrawableId(String resName, int fallbackResId) {
        try {
            int id = getResources().getIdentifier(resName, "drawable", getPackageName());
            return (id != 0) ? id : fallbackResId;
        } catch (Exception e) {
            return fallbackResId;
        }
    }

    // ระบบกำหนดประเภทหน้าต่างทับซ้อน (Overlay Type) ตามเวอร์ชัน Android
    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ระบบเริ่มการกดซ้ำรัวๆ (Rapid Fire Start)
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
        Integer rate = buttonRateMap.get(b);
        rt.setRate(rate != null ? rate : 10);
        Integer pms = buttonPressMsMap.get(b);
        rt.setPressMs(pms != null ? pms : 25);
        rt.start();
    }

    // ระบบหยุดการกดซ้ำรัวๆ
    private void rapidStop(View b) {
        if (b == null) return;
        RapidTap rt = rapidTapMap.get(b);
        if (rt != null) rt.stop();
    }

    private void stopAllRapidTaps() {
        for (RapidTap rt : rapidTapMap.values()) rt.stop();
        rapidMouseHeld = null;
    }

    private void removeRapidData(View b) {
        RapidTap rt = rapidTapMap.remove(b);
        if (rt != null) rt.release();
        buttonRateMap.remove(b);
        buttonPressMsMap.remove(b);
    }

    private void clearAllRapidData() {
        for (RapidTap rt : rapidTapMap.values()) rt.release();
        rapidTapMap.clear();
        buttonRateMap.clear();
        buttonPressMsMap.clear();
        rapidMouseHeld = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            maxScreenWidth = getResources().getDisplayMetrics().widthPixels;
            maxScreenHeight = getResources().getDisplayMetrics().heightPixels;

            TouchSimulator.setContext(this);
            loadSettings();
            startForegroundServiceForAndroid16();

            // สร้างหน้าต่างและเลย์เอาต์ทั้งหมด
            createEditModeBackground();
            createFloatingIcon();
            createMenuWindow();
            createAddMenuWindow();
            createIconSettingsWindow();
            createCloudWindow();

            mouseController = new MouseController(this, windowManager, maxScreenWidth, maxScreenHeight, cursorSize, mouseSensitivity, this);

            monitorThread.start();
            monitorHandler = new Handler(monitorThread.getLooper());

            checkFirstTimeLaunch();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onMouseClickUI(float x, float y, int action) {
        return dispatchTouchToUi(x, y, action);
    }

    // ระบบจำลองการคลิกซ้ายของเมาส์
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
            rapidStart(mapped);
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
            rapidStop(rapidMouseHeld);
            rapidMouseHeld = null;
            return;
        }
        if (!isMouseMode || mouseLeftToken == 0) return;
        long token = mouseLeftToken;
        mouseLeftToken = 0;
        TouchSimulator.simulateUp(token, MOUSE_LEFT_POINTER, mouseLeftX, mouseLeftY);
    }

    // ระบบจำลองการคลิกขวาของเมาส์
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
                Toast.makeText(this, "ไม่สามารถตั้งปุ่มซ้ำได้! (ปุ่มนี้ถูกใช้งานไปแล้ว)", Toast.LENGTH_SHORT).show();
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
        if (dPadController != null) dPadController.release();
        stopAllRapidTaps();
        onMouseLockedLeftUp();
    }

    // ระบบ Foreground Service สำหรับ Android 16 และรุ่นใหม่
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

    // ระบบหน้าต่างแจ้งเตือน
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                        "เพื่อให้แอปทำงานได้อย่างเต็มประสิทธิภาพและไม่ถูกระบบ Android ฆ่าโปรแกรม:\n\n" +
                        "1️⃣ แนะนำให้ตั้งค่าการใช้แบตเตอรี่เป็น 'ไม่จำกัด' (Unrestricted)\n" +
                        "2️⃣ ตรวจสอบสิทธิ์ Shizuku ให้เรียบร้อยก่อนใช้งาน",
                        () -> prefs.edit().putBoolean("is_first_launch", false).apply()
                    );
                }, 1500);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    // ระบบปลดล็อกการสัมผัสผ่าน Shizuku
    private void unblockUntrustedTouches() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        new Thread(() -> {
            try {
                java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                m.setAccessible(true);
                Process p = (Process) m.invoke(null, new Object[]{
                        new String[]{"sh", "-c",
                                "settings put global block_untrusted_touches 0; "
                                + "settings get global block_untrusted_touches"},
                        null, null});
                java.io.BufferedReader out = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                java.io.BufferedReader err = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getErrorStream()));
                String o = out.readLine();
                String e = err.readLine();
                p.waitFor();
                Log.i("UNBLOCK", "value=" + o + " err=" + e);
            } catch (Throwable t) {
                Log.e("UNBLOCK", "failed", t);
            }
        }).start();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("MynovoSettings", MODE_PRIVATE);
        currentAlpha = Math.max(0.4f, prefs.getFloat("alpha", 0.9f));
        mouseSensitivity = prefs.getFloat("sensitivity", 4.0f);
        cursorSize = prefs.getInt("cursorSize", dpToPx(36));
        dpadSizeDp = prefs.getInt("dpadSizeDp", 160);
        dpadSize = dpToPx(dpadSizeDp);
        globalBtnSizeDp = Math.max(20, Math.min(96, prefs.getInt("btnSizeDp", 32)));
        globalBtnAlpha = Math.max(0.1f, Math.min(1.0f, prefs.getFloat("btnViewAlpha", 1.0f)));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences("MynovoSettings", MODE_PRIVATE);
        prefs.edit()
             .putFloat("alpha", currentAlpha)
             .putFloat("sensitivity", mouseSensitivity)
             .putInt("cursorSize", cursorSize)
             .putInt("dpadSizeDp", dpadSizeDp)
             .putInt("btnSizeDp", globalBtnSizeDp)
             .putFloat("btnViewAlpha", globalBtnAlpha)
             .apply();
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
            targetSeen = false;
            ownAppCount = 0;
            awaySince = 0;
            unblockUntrustedTouches();
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

    // ระบบอัปเดตหน้าจอโหมดแก้ไข (Edit Mode UI)
    private void updateEditModeUI() {
        if (destroyed) return;
        boolean editMode = isEditModeActive();

        if (editMode) stopAllRapidTaps();

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
                        if (!(isWaitingForKey && pendingMappingButton == container)) {
                            restoreLabel(container);
                        }
                    } else if (eyeRes != 0) {
                        mtv.setBackgroundResource(eyeRes); // แสดงรูป btn_fps_eye.png เมื่ออยู่นอกโหมดแก้ไข
                        mtv.setText("");
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
    }

    // ระบบโหมดพิมพ์ (Typing Mode)
    private void setTypingMode(boolean typing) {
        if (destroyed || mouseController == null || isTypingMode == typing) return;
        if (typing) {
            if (isWaitingForKey) cancelKeyWait();
            if (dPadController != null) dPadController.release();
            stopAllRapidTaps();
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
        } else {
            isTypingMode = false;
            mouseController.setTypingMode(false);
            isMouseMode = mouseController.isMouseActive();
            if (floatingView instanceof ImageView) ((ImageView) floatingView).clearColorFilter();
        }
    }

    // ระบบสร้างไอคอนลอย (Floating Icon)
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

    // ระบบสร้างหน้าต่างเมนูหลัก (Menu Window)
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
                    getDrawableId("btn_keyboard", getDrawableId("btn_mouse", R.drawable.btn_b)),
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
                Toast.makeText(this, "ไม่สามารถตั้งปุ่มซ้ำได้! (ปุ่มนี้ถูกใช้งานไปแล้ว)", Toast.LENGTH_SHORT).show();
                cancelKeyWait();
                return;
            }
        }
        finishAssign(id, "");
    }

    // ระบบกระจายการสัมผัสไปยัง UI ต่างๆ บนหน้าจอ
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

    // ระบบรอรับค่าปุ่มคีย์บอร์ดเมื่อผู้ใช้กดตั้งค่าปุ่ม
    private void startKeyWait(final View container) {
        if ("FIRE".equals(container.getTag())) {
            Toast.makeText(this, "ปุ่มยิงถูกล็อกไว้เป็นคลิกซ้ายอัตโนมัติแล้ว", Toast.LENGTH_SHORT).show();
            return;
        }

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
        boolean isWASD = dpadKeyOf(ev) != -1;
        String btnType = btn.getTag() != null ? btn.getTag().toString() : "NORMAL";

        if (!"DPAD".equals(btnType) && isWASD) {
            Toast.makeText(this, "ตั้งซ้ำไม่ได้! (W, A, S, D ล็อคไว้สำหรับ D-Pad เท่านั้น)", Toast.LENGTH_SHORT).show();
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
                    ? "ปุ่มนี้ตั้งซ้ำไม่ได้! (SPACE / คลิกซ้าย / คลิกขวา / ปุ่มมาโครเมาส์)"
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

    // ระบบจัดการการกดปุ่มคีย์บอร์ดทั้งหมด (Key Handler)
    public boolean handleKey(KeyEvent event) {
        if (destroyed) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isWaitingForKey) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) cancelKeyWait();
                return true;
            }
            return false;
        }

        boolean isDown = (event.getAction() == KeyEvent.ACTION_DOWN);
        boolean isUp = (event.getAction() == KeyEvent.ACTION_UP);

        int dpadKey = dpadKeyOf(event);
        if (dpadKey != -1) {
            if (!isEditModeActive()) {
                if (dPadController != null) {
                    if (isDown || isUp) {
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
                    rapidStop(en.getKey());
                    h = true;
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
        int tapCount = 0;
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
                    if (!isEditModeActive() && isTargetInForeground) rapidStart(b);
                    handled = true;
                    continue;
                }

                int baseKey = event.getScanCode() != 0 ? 1000 + event.getScanCode() : keyCode;
                if (tapDebounced(baseKey * 31 + System.identityHashCode(b))) { handled = true; continue; }

                int[] loc = new int[2];
                b.getLocationOnScreen(loc);
                int ptr = (tapCount == 0) ? 0 : 29 + tapCount;
                tapCount++;
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
            bar.setProgress(Math.max(0, bar.getProgress() - 1)); // เปลี่ยนเป็น 1 เพื่อลบทีละ 0.01
            savePresetToCloud();
        });
        TextView plus = createStepButton("+");
        plus.setOnClickListener(v -> {
            bar.setProgress(Math.min(bar.getMax(), bar.getProgress() + 1)); // เปลี่ยนเป็น 1 เพื่อเพิ่มทีละ 0.01
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

    // ระบบสลับโหมดล็อก/ปลดล็อกเมาส์
    private void toggleMouseLock() {
        if (isTypingMode) return;
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

    // ระบบสร้างหน้าต่างเพิ่มตัวควบคุม (Add Menu Window)
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
            
            // ดึงไอคอน ic_fire_box.png มาใช้เป็นปุ่มยิง
            int idFire = getDrawableId("ic_fire_box", R.drawable.btn_b);

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
                "ล็อกเมาส์", idFps, v -> addNewMappingButton("MOUSE_LOCK", defaultCenterX, defaultCenterY, false, -1, true),
                "ปัด", idSwipe, v -> addNewMappingButton("SWIPE", defaultCenterX, defaultCenterY, false, -1, true)
            ));

            rows.addView(createButtonRow(
                "ยิง", idFire, v -> {
                    int fireKeyId = makeKeyId(60001, 0);
                    addNewMappingButton("FIRE", defaultCenterX, defaultCenterY, false, fireKeyId, true);
                },
                null, 0, null
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

    // ระบบสร้างหน้าต่างตั้งค่าระบบ (Settings Window)
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

            layout.addView(createSettingsLabel("ความโปร่งใสของไอคอนเมนู (Transparency)"));
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

            int curBtnPct = Math.max(10, Math.min(100, Math.round(globalBtnAlpha * 100f)));
            final TextView tvBtnAlpha = createSettingsLabel("ความโปร่งใสปุ่มทุกปุ่ม (Button Opacity): " + curBtnPct + "%");
            layout.addView(tvBtnAlpha);
            SeekBar btnAlphaBar = new SeekBar(this);
            btnAlphaBar.setMax(90);
            btnAlphaBar.setProgress(curBtnPct - 10);
            btnAlphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int pct = 10 + progress;
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
            int currentSizeDp = Math.round(cursorSize / getResources().getDisplayMetrics().density);
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

    // ระบบรีเซ็ตปุ่มควบคุมทั้งหมด
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

    // ระบบจัดการพรีเซ็ตคลาวด์ (Cloud Preset Window)
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
                    "ปุ่มทั้งหมดบนหน้าจอของเกมนี้จะถูกลบ และล้างพรีเซ็ตที่บันทึกไว้\n\nต้องการดำเนินการต่อหรือไม่?",
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

    // ระบบสร้างปุ่มควบคุมใหม่บนหน้าจอ (Add Mapping Button)
    private void addNewMappingButton(String type, int posX, int posY, boolean askKey, int savedKey, boolean useStagger) {
        if (destroyed) return;
        Log.i("ADDBTN", "add type=" + type);
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
                    GradientDrawable circleBg = new GradientDrawable();
                    circleBg.setShape(GradientDrawable.OVAL);
                    circleBg.setColor(Color.parseColor("#4D000000"));
                    circleBg.setStroke(dpToPx(1), Color.WHITE);
                    dpadImgView.setBackground(circleBg);
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
            mapBtn.setTextSize(10f);
            mapBtn.setMaxLines(1);
            mapBtn.setPadding(dpToPx(1), 0, dpToPx(1), 0);
            mapBtn.setShadowLayer(3f, 0f, 0f, Color.BLACK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mapBtn.setAutoSizeTextTypeUniformWithConfiguration(6, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            }

            if (type.equals("DPAD")) {
                mapBtn.setText("");
            } else {
                String label;
                if (type.equals("HIDE_MOUSE")) { label = "👁️"; }
                else if (type.equals("RAPID_FIRE")) { label = "⚡"; }
                else if (type.equals("MOUSE_LOCK")) { label = "🖱️"; }
                else if (type.equals("FIRE")) {
                    int fireRes = getDrawableId("btn_fire", 0);
                    if (fireRes != 0) {
                        mapBtn.setBackgroundResource(fireRes);
                        label = "";
                    } else {
                        label = "Fire";
                    }
                }
                else {
                    label = "?";
                    GradientDrawable circleBg = new GradientDrawable();
                    circleBg.setShape(GradientDrawable.OVAL);
                    circleBg.setColor(Color.parseColor("#4D000000"));
                    circleBg.setStroke(dpToPx(1), Color.WHITE);
                    mapBtn.setBackground(circleBg);
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
        } catch (Exception e) {
            Log.e("ADDBTN", "addNewMappingButton failed type=" + type, e);
        }
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
        globalBtnAlpha = Math.max(0.1f, Math.min(1.0f, a));
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
    }

    // ระบบหน้าต่างตั้งค่าปุ่มรายตัว (Button Settings Window)
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
            }

            if ("RAPID_FIRE".equals(type)) {
                Integer r = buttonRateMap.get(container);
                addIntRow(layout, "ความเร็วแตะ (ครั้ง/วิ): ", 1, 30, r != null ? r : 10,
                        v -> buttonRateMap.put(container, v));
                Integer p = buttonPressMsMap.get(container);
                addIntRow(layout, "เวลากดต่อครั้ง (ms): ", 16, 100, p != null ? p : 25,
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ระบบบันทึกพรีเซ็ตไปยัง SharedPreferences
    private void savePresetToCloud() {
        final String pkg = targetPackage;
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
                Integer rr = buttonRateMap.get(btn);
                if (rr != null) obj.put("rate", (int) rr);
                Integer pp = buttonPressMsMap.get(btn);
                if (pp != null) obj.put("pms", (int) pp);
                jsonArray.put(obj);
            }
            json = jsonArray.toString();
        } catch (Exception e) {
            return;
        }
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("MynovoMappingPrefs", MODE_PRIVATE);
                prefs.edit().putString(pkg + "_preset", json).apply();
            } catch (Exception ignored) {}
        }).start();
    }

    // ระบบโหลดพรีเซ็ตจาก SharedPreferences
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
                clearAllRapidData();
                if (dPadController != null) dPadController.release();
                dPadController = null;

                SharedPreferences prefs = getSharedPreferences("MynovoMappingPrefs", MODE_PRIVATE);
                String jsonStr = prefs.getString(targetPackage + "_preset", "");
                if (!jsonStr.isEmpty()) {
                    JSONArray jsonArray = new JSONArray(jsonStr);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject obj = jsonArray.getJSONObject(i);
                        int x = obj.getInt("x");
                        int y = obj.getInt("y");
                        String type = obj.has("type") ? obj.getString("type") : "NORMAL";
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
                            if (obj.has("rate")) buttonRateMap.put(added, obj.getInt("rate"));
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

    // ระบบตรวจสอบเกมเป้าหมายที่กำลังรันอยู่เบื้องหลัง (App Monitor)
    private Runnable checkAppRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            try {
                String currentApp = getForegroundAppRobust();

                boolean nowForeground = targetPackage.isEmpty() || targetPackage.equals(currentApp);

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
        boolean prevForeground = isTargetInForeground;
        isTargetInForeground = nowForeground;

        if (!prevForeground && nowForeground) {
            activeTouchPointers.clear();
            TouchSimulator.releaseTouch();
            mouseLeftToken = 0;
        }

        if (floatingView == null) return;

        if (nowForeground) {
            if (!isEditModeActive() && floatingView.getVisibility() != View.VISIBLE) {
                floatingView.setVisibility(View.VISIBLE);
                for (View btn : mappingButtons) btn.setVisibility(View.VISIBLE);
            }
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
            } catch (Exception ignored) {}
            if (isChecking && !destroyed) handler.postDelayed(this, 400);
        }
    };

    private java.lang.reflect.Method shizukuNewProcess = null;
    private long lastShizukuCheck = 0;
    private String fgSource = "usage";

    private String getTopAppViaShizuku() {
        try {
            if (!Shizuku.pingBinder()) return null;
            if (shizukuNewProcess == null) {
                shizukuNewProcess = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                shizukuNewProcess.setAccessible(true);
            }
            Process proc = (Process) shizukuNewProcess.invoke(null, new Object[]{
                    new String[]{"sh", "-c", "dumpsys activity activities | grep -E 'ResumedActivity'"}, null, null});
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile("u\\d+\\s+([A-Za-z0-9_.]+)/");
            String top = null, fallback = null;
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher m = pat.matcher(line);
                if (!m.find()) continue;
                if (line.contains("topResumedActivity")) { if (top == null) top = m.group(1); }
                else if (fallback == null) fallback = m.group(1);
            }
            try { reader.close(); } catch (Exception ignored) {}
            try { proc.waitFor(); } catch (Exception ignored) {}
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

        if (!shizukuBusy && now - lastShizukuCheck >= 2000) {
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
        if (sz != null && shizukuAt > usageChangedAt && now - shizukuAt < 5000) {
            fgSource = "shizuku";
            return sz;
        }
        fgSource = "usage";
        return usage;
    }

    private String lastForegroundApp = "None";
    private String lastForegroundClass = "";
    private long lastUsageQueryTime = 0;

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
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForegroundApp = pkg;
                    lastForegroundClass = cls != null ? cls : "";
                } else if (type == 23 && pkg != null && pkg.equals(lastForegroundApp)
                        && cls != null && cls.equals(lastForegroundClass)) {
                    lastForegroundApp = "None";
                    lastForegroundClass = "";
                }
            }
            lastUsageQueryTime = now;
            return lastForegroundApp;
        } catch (Exception e) { return lastForegroundApp; }
    }

    // ระบบทำลายการทำงานและเคลียร์หน่วยความจำ (Destroy Service)
    @Override
    public void onDestroy() {
        destroyed = true;
        isChecking = false;

        if (killOnDestroy) {
            final int pid = android.os.Process.myPid();
            new Thread(() -> {
                try { TouchSimulator.releaseTouch(); } catch (Exception ignored) {}
                try { Thread.sleep(150); } catch (Exception ignored) {}
                android.os.Process.killProcess(pid);
            }).start();
        }

        try { handler.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try {
            if (monitorHandler != null) monitorHandler.removeCallbacksAndMessages(null);
            monitorThread.quitSafely();
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

        try { saveSettings(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}

        if (!killOnDestroy) {
            new Thread(() -> {
                try { TouchSimulator.releaseTouch(); } catch (Exception ignored) {}
            }).start();
        }

        super.onDestroy();
    }
}