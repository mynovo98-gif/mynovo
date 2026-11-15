package com.mynovo.mynovo1;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

public class TouchSimulator {
    private static final String TAG = "TouchSimulator";

    // เปิดเมื่อต้องการ debug (Log ทุก DOWN/UP ทำให้ช้าลงนิดหน่อย)
    private static final boolean DEBUG_LOG = false;

    // เวลาค้างนิ้วของ simulateClick (ms) เกมที่รับปุ่มไม่ติดให้เพิ่ม (เช่น 40-60) ถ้าอยากให้ไวสุดให้ลด (เช่น 10-15)
    private static final long CLICK_HOLD_MS = 20L;

    // accepted=false ติดกันกี่ครั้งถึงจะแจ้งผู้ใช้ว่า ROM อาจบล็อก
    private static final int REJECT_STREAK_LIMIT = 8;

    private static volatile Object iInputManager = null;
    private static volatile Method injectInputEventMethod = null;

    // 0 = ASYNC (ไม่บล็อก)
    private static final int INJECT_MODE = 0;

    private static final HandlerThread clickThread = new HandlerThread("TouchSimClick");
    private static final Handler clickHandler;

    // เธรดแยกสำหรับส่ง event เข้าระบบ (binder call ผ่าน Shizuku / เขียน socket ใช้เวลา) main thread จะได้ไม่โดนบล็อก
    // และปุ่มแตะไม่ต้องรอคิวหลัง event เมาส์ที่ถี่มาก
    private static final HandlerThread injectThread =
            new HandlerThread("TouchSimInject", Process.THREAD_PRIORITY_URGENT_DISPLAY);
    private static final Handler injectHandler;
    static {
        clickThread.start();
        clickHandler = new Handler(clickThread.getLooper());
        injectThread.start();
        injectHandler = new Handler(injectThread.getLooper());
    }

    // คิว event ที่รอส่ง (เรียงลำดับเดิมเสมอ)
    private static final ArrayDeque<MotionEvent> pendingEvents = new ArrayDeque<>();
    private static boolean drainScheduled = false;

    private static Context appContext = null;
    private static long lastInitTime = 0;

    // นับ accepted=false ติดกัน (ใช้เฉพาะเธรดส่ง event)
    private static int failStreak = 0;

    // cache ผลเช็คสิทธิ์ Shizuku กันเรียก binder ซ้ำทุกครั้งที่กด
    private static volatile long permOkUntil = 0;

    // logical id (ที่แอปเราใช้) -> state
    private static final ConcurrentHashMap<Integer, PointerState> activePointers = new ConcurrentHashMap<>();

    private static long gestureDownTime = 0;
    private static long downSeq = 0;

    // [เพิ่ม] buffer ใช้ซ้ำสำหรับสร้าง MotionEvent (MotionEvent.obtain คัดลอกค่าไปเก็บเองอยู่แล้ว จึงใช้ซ้ำได้ปลอดภัย)
    // เดิมทุก MOVE สร้าง ArrayList + sort + PointerProperties/PointerCoords ใหม่หมด ที่เมาส์ถี่ๆ ทำให้เกิด GC แล้วกระตุก
    private static final int MAX_P = 10;
    private static final MotionEvent.PointerProperties[] PROPS = new MotionEvent.PointerProperties[MAX_P];
    private static final MotionEvent.PointerCoords[] COORDS = new MotionEvent.PointerCoords[MAX_P];
    // [เพิ่ม] cache ลำดับนิ้ว (เรียงตาม seq) สร้างใหม่เฉพาะตอนจำนวนนิ้วเปลี่ยน (DOWN/UP/CANCEL)
    private static final int[] cacheIds = new int[MAX_P];
    private static final PointerState[] cacheStates = new PointerState[MAX_P];
    private static int cacheSize = 0;
    private static boolean cacheValid = false;
    static {
        for (int i = 0; i < MAX_P; i++) {
            PROPS[i] = new MotionEvent.PointerProperties();
            COORDS[i] = new MotionEvent.PointerCoords();
        }
    }

    // deviceId ของ touchscreen จริง (บางเกมกรอง deviceId 0 / virtual ทิ้ง)
    private static int touchDeviceId = 0;
    private static volatile boolean touchDeviceResolved = false;

    // ผู้ใช้กด Deactivate แล้ว = เลิกใช้ Shizuku (Shizuku ยังรันอยู่ แต่แอปไม่ใช้ส่ง event) เก็บไว้ใน SharedPreferences
    private static final String PREF_NAME = "MynovoPrefs";
    private static final String KEY_SHIZUKU_DISABLED = "shizukuDisabled";
    private static volatile boolean shizukuEnabled = true;

    // จอที่เกมอยู่ (DeX = จอภายนอก) FloatingService เรียก setDisplayId ให้ตอนเริ่มทำงาน
    // ไม่ตั้ง = จอหลัก (0) ถ้าไม่ใส่ displayId ให้ MotionEvent ระบบจะส่งทัชไปจอมือถือ (ทำให้จอมือถือเลื่อนตามเมาส์)
    private static volatile int targetDisplayId = 0;
    private static volatile Method setDisplayIdMethod = null;
    private static volatile boolean setDisplayIdResolved = false;

    public static void setDisplayId(int id) {
        if (id == targetDisplayId) return;
        // เปลี่ยนจอกลางทาง: ปล่อยนิ้วค้างเดิมกันค้างบนจอเก่า
        try { releaseTouch(); } catch (Throwable ignored) {}
        targetDisplayId = id;
        Log.i(TAG, "target displayId=" + id);
    }

    public static int getDisplayId() {
        return targetDisplayId;
    }

    // ใส่ displayId ให้ MotionEvent (InputEvent.setDisplayId เป็น hidden API เลยใช้ reflection)
    private static void applyDisplayId(MotionEvent ev) {
        final int id = targetDisplayId;
        if (id == 0) return;
        try {
            if (!setDisplayIdResolved) {
                try {
                    Method m = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                    m.setAccessible(true);
                    setDisplayIdMethod = m;
                } catch (Throwable t) {
                    Log.w(TAG, "InputEvent.setDisplayId not available: " + t);
                }
                setDisplayIdResolved = true;
            }
            Method m = setDisplayIdMethod;
            if (m != null) m.invoke(ev, id);
        } catch (Throwable t) {
            Log.w(TAG, "applyDisplayId failed: " + rootCause(t));
        }
    }

    public static boolean isShizukuEnabled() {
        return shizukuEnabled;
    }

    public static void setShizukuEnabled(Context c, boolean enabled) {
        shizukuEnabled = enabled;
        if (c != null) {
            c.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SHIZUKU_DISABLED, !enabled).apply();
        }
        if (!enabled) {
            iInputManager = null;
            injectInputEventMethod = null;
        }
    }

    private static class PointerState {
        float x, y;
        long downTime;
        long seq;
        int eventId; // id ที่ใส่ใน MotionEvent จริง (ต่ำสุดที่ว่าง เริ่มที่ 0 เหมือนจอจริง)

        PointerState(float x, float y, long downTime, long seq, int eventId) {
            this.x = x;
            this.y = y;
            this.downTime = downTime;
            this.seq = seq;
            this.eventId = eventId;
        }
    }

    public static void setContext(Context c) {
        if (c != null) {
            appContext = c.getApplicationContext();
            // โหลดสถานะ "ปิด Shizuku" ที่ผู้ใช้เคยกด Deactivate ไว้
            shizukuEnabled = !appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SHIZUKU_DISABLED, false);
            // ให้ client ของ shell server รู้จัก context แล้วลองเชื่อมรอไว้เลย (ถ้า activate.sh รันอยู่แล้ว)
            ShellServerClient.setContext(c);
            ShellServerClient.ensureConnectedAsync();
        }
    }

    private static Throwable rootCause(Throwable e) {
        if (e instanceof InvocationTargetException && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }

    private static boolean shizukuPermissionOk() {
        long now = SystemClock.uptimeMillis();
        if (now < permOkUntil) return true;
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            permOkUntil = now + 3000;
            return true;
        }
        permOkUntil = 0;
        return false;
    }

    // มีทางส่ง event ให้ใช้ไหม: shell server (activate.sh) หรือ Shizuku อย่างใดอย่างหนึ่ง
    private static boolean accessOk() {
        if (ShellServerClient.isConnected()) return true;
        ShellServerClient.ensureConnectedAsync();
        try {
            return shizukuEnabled && shizukuPermissionOk();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean backendReady() {
        return ShellServerClient.isConnected()
                || (iInputManager != null && injectInputEventMethod != null);
    }

    private static int findTouchDeviceId() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && !d.isVirtual()
                        && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                    return id;
                }
            }
            // ไม่เจอตัวที่ไม่ virtual ลองตัวไหนก็ได้ที่เป็น touchscreen
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null
                        && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) {
                    return id;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findTouchDeviceId failed", t);
        }
        return 0;
    }

    private static synchronized void initIInputManager() {
        if (!shizukuEnabled) return;
        long now = SystemClock.uptimeMillis();
        if (iInputManager != null && injectInputEventMethod != null) return;
        if (now - lastInitTime < 2000) {
            Log.w(TAG, "init throttled (ลองใหม่ภายใน 2 วินาที)");
            return;
        }
        lastInitTime = now;

        try {
            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku binder NOT alive (Shizuku ไม่ได้ทำงาน)");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Shizuku permission NOT granted");
                return;
            }

            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            IBinder rawBinder = (IBinder) getServiceMethod.invoke(null, Context.INPUT_SERVICE);

            if (rawBinder != null) {
                IBinder wrappedBinder = new ShizukuBinderWrapper(rawBinder);
                Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Object im = asInterface.invoke(null, wrappedBinder);

                Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
                Method inject = iInputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
                inject.setAccessible(true);

                touchDeviceId = findTouchDeviceId();
                touchDeviceResolved = true;
                injectInputEventMethod = inject;
                iInputManager = im;
                Log.i(TAG, "IInputManager init OK, touchDeviceId=" + touchDeviceId);
            } else {
                Log.e(TAG, "ServiceManager.getService(input) returned null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init IInputManager via Reflection", rootCause(e));
        }
    }

    // เตรียมทางส่ง event: ถ้ายังไม่เชื่อม shell server ให้ขอเชื่อมเบื้องหลัง แล้วลอง Shizuku ไปก่อน
    private static synchronized void initBackend() {
        if (backendReady()) return;
        ShellServerClient.ensureConnectedAsync();
        if (ShellServerClient.isConnected()) return;
        initIInputManager();
    }

    private static int lowestFreeEventId() {
        int candidate = 0;
        while (true) {
            boolean used = false;
            for (PointerState s : activePointers.values()) {
                if (s.eventId == candidate) { used = true; break; }
            }
            if (!used) return candidate;
            candidate++;
        }
    }

    // [เพิ่ม] สร้าง cache ลำดับนิ้วใหม่ (เรียกเฉพาะตอนจำนวนนิ้วเปลี่ยน) ต้องเรียกใน synchronized(TouchSimulator.class)
    private static void rebuildCache() {
        List<Map.Entry<Integer, PointerState>> list = new ArrayList<>(activePointers.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(a.getValue().seq, b.getValue().seq));
        int n = Math.min(list.size(), MAX_P);
        for (int i = 0; i < n; i++) {
            cacheIds[i] = list.get(i).getKey();
            cacheStates[i] = list.get(i).getValue();
        }
        cacheSize = n;
        cacheValid = true;
    }

    // ===== คิวส่ง event =====

    private static void enqueue(MotionEvent ev, boolean isMove) {
        synchronized (pendingEvents) {
            if (isMove) {
                // MOVE ที่ยังไม่ถูกส่งและอยู่ท้ายคิว ถูกแทนด้วยตัวใหม่ (ตำแหน่งล่าสุดเท่านั้นที่สำคัญ)
                MotionEvent last = pendingEvents.peekLast();
                if (last != null && last.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    pendingEvents.pollLast();
                    last.recycle();
                }
            }
            pendingEvents.addLast(ev);
            if (!drainScheduled) {
                drainScheduled = true;
                injectHandler.post(drainRunnable);
            }
        }
    }

    private static void clearPending() {
        synchronized (pendingEvents) {
            MotionEvent e;
            while ((e = pendingEvents.pollFirst()) != null) e.recycle();
        }
    }

    private static void handleInjectFailure(boolean resetBinder) {
        synchronized (TouchSimulator.class) {
            if (resetBinder) {
                iInputManager = null;
                injectInputEventMethod = null;
            }
            activePointers.clear();
            cacheValid = false;
            gestureDownTime = 0;
        }
        clearPending();
    }

    private static final Runnable drainRunnable = new Runnable() {
        @Override
        public void run() {
            while (true) {
                MotionEvent ev;
                synchronized (pendingEvents) {
                    ev = pendingEvents.pollFirst();
                    if (ev == null) {
                        drainScheduled = false;
                        return;
                    }
                }

                // ถ้าเชื่อม shell server อยู่ใช้ทางนั้นก่อน ไม่งั้นใช้ Shizuku
                // (อยากให้ Shizuku มาก่อน ให้สลับลำดับตรงนี้)
                final boolean viaServer = ShellServerClient.isConnected();
                final Object im = iInputManager;
                final Method m = injectInputEventMethod;
                if (!viaServer && (im == null || m == null)) {
                    ev.recycle();
                    continue;
                }

                final int action = ev.getAction();
                boolean accepted = true;
                boolean threw = false;
                boolean serverFailed = false;
                Throwable err = null;
                try {
                    if (viaServer) {
                        serverFailed = !ShellServerClient.send(ev);
                    } else {
                        Object result = m.invoke(im, ev, INJECT_MODE);
                        if (result instanceof Boolean) accepted = (Boolean) result;
                    }
                } catch (Throwable t) {
                    threw = true;
                    err = rootCause(t);
                    Log.e(TAG, "Shizuku multi-touch injection error", err);
                } finally {
                    ev.recycle();
                }

                if (DEBUG_LOG && (action != MotionEvent.ACTION_MOVE || !accepted)) {
                    Log.d(TAG, "inject " + MotionEvent.actionToString(action)
                            + " via=" + (viaServer ? "server" : "shizuku") + " accepted=" + accepted);
                }

                if (accepted && !threw && !serverFailed) failStreak = 0;

                if (serverFailed) {
                    Log.w(TAG, "shell server send failed, resetting state");
                    handleInjectFailure(false);
                    ShellServerClient.ensureConnectedAsync();
                } else if (threw) {
                    if (err instanceof SecurityException) {
                        RomGuide.markNeeded(appContext, "ROM ปฏิเสธสิทธิ์");
                    }
                    handleInjectFailure(true);
                } else if (!accepted) {
                    // ไม่ล้างสถานะนิ้ว (กันกล้องหยุดเวลามีหลายนิ้วชนกัน) แค่นับไว้แจ้งผู้ใช้
                    Log.w(TAG, "injectInputEvent returned false (ignored, keeping pointer state)");
                    if (++failStreak >= REJECT_STREAK_LIMIT) {
                        failStreak = 0;
                        RomGuide.markNeeded(appContext, "ระบบไม่รับ event");
                    }
                }
            }
        }
    };

    private static synchronized void injectSyncState(int targetPointerId, int action, float x, float y) {
        try {
            final boolean isDown = action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_POINTER_DOWN;
            final boolean isUp = action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP;
            final boolean isCancel = action == MotionEvent.ACTION_CANCEL;

            if (!backendReady()) {
                if (!isDown) return;
                initBackend();
            }
            if (!backendReady()) {
                Log.w(TAG, "DOWN dropped: ยังไม่มีทางส่ง event (activate.sh ยังไม่รัน และ Shizuku ยังไม่พร้อม)");
                return;
            }
            if (!touchDeviceResolved) {
                touchDeviceId = findTouchDeviceId();
                touchDeviceResolved = true;
            }

            long now = SystemClock.uptimeMillis();

            if (isDown) {
                PointerState existing = activePointers.get(targetPointerId);
                if (existing != null) {
                    injectSyncState(targetPointerId, MotionEvent.ACTION_UP, existing.x, existing.y);
                    now = SystemClock.uptimeMillis();
                }
                if (activePointers.isEmpty()) gestureDownTime = now;
                activePointers.put(targetPointerId,
                        new PointerState(x, y, now, ++downSeq, lowestFreeEventId()));
                cacheValid = false;
            } else if (action == MotionEvent.ACTION_MOVE) {
                PointerState state = activePointers.get(targetPointerId);
                if (state == null) return;
                state.x = x;
                state.y = y;
            } else if (isUp) {
                PointerState state = activePointers.get(targetPointerId);
                if (state == null) return;
                state.x = x;
                state.y = y;
            } else if (isCancel) {
                if (activePointers.isEmpty()) return;
            } else {
                return;
            }

            // [แก้ไข] ใช้ cache + buffer ซ้ำ ไม่สร้าง object ใหม่ทุก MOVE
            if (!cacheValid) rebuildCache();
            final int size = cacheSize;
            if (size == 0) return;
            int actionPointerIndex = 0;

            for (int i = 0; i < size; i++) {
                PointerState state = cacheStates[i];

                PROPS[i].clear();
                PROPS[i].id = state.eventId;
                PROPS[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

                COORDS[i].clear();
                COORDS[i].x = state.x;
                COORDS[i].y = state.y;
                COORDS[i].pressure = 1.0f;
                COORDS[i].size = 1.0f;

                if (cacheIds[i] == targetPointerId) actionPointerIndex = i;
            }

            int finalAction;
            if (action == MotionEvent.ACTION_MOVE) {
                finalAction = MotionEvent.ACTION_MOVE;
            } else if (isCancel) {
                finalAction = MotionEvent.ACTION_CANCEL;
            } else if (isDown) {
                finalAction = (size > 1)
                        ? (MotionEvent.ACTION_POINTER_DOWN | (actionPointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                        : MotionEvent.ACTION_DOWN;
            } else {
                finalAction = (size > 1)
                        ? (MotionEvent.ACTION_POINTER_UP | (actionPointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                        : MotionEvent.ACTION_UP;
            }

            MotionEvent event = MotionEvent.obtain(
                    gestureDownTime, now, finalAction,
                    size, PROPS, COORDS,
                    0, 0, 1.0f, 1.0f,
                    touchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            );

            // ส่งทัชไปจอที่เกมอยู่ (DeX) ไม่ใช่จอมือถือ
            applyDisplayId(event);

            // ส่งเข้าคิว ให้เธรดส่ง event จัดการ (ไม่บล็อกตรงนี้)
            enqueue(event, finalAction == MotionEvent.ACTION_MOVE);

            if (isUp) {
                activePointers.remove(targetPointerId);
                cacheValid = false;
            } else if (isCancel) {
                activePointers.clear();
                cacheValid = false;
            }
            if (activePointers.isEmpty()) {
                gestureDownTime = 0;
            }

        } catch (Exception e) {
            Log.e(TAG, "multi-touch injection error", rootCause(e));
            iInputManager = null;
            injectInputEventMethod = null;
            activePointers.clear();
            cacheValid = false;
            gestureDownTime = 0;
            clearPending();
        }
    }

    public static synchronized boolean isPointerDown(int pointerId) {
        return activePointers.containsKey(pointerId);
    }

    public static long simulateDown(final int pointerId, final int x, final int y) {
        if (DEBUG_LOG) Log.d(TAG, "simulateDown ptr=" + pointerId + " at(" + x + "," + y + ")");
        try {
            if (!accessOk()) {
                Log.w(TAG, "simulateDown: ไม่มีทางส่ง event (shell server ไม่เชื่อม และ Shizuku ไม่มีสิทธิ์)");
                return 0;
            }
            final long downTime = SystemClock.uptimeMillis();
            injectSyncState(pointerId, MotionEvent.ACTION_DOWN, (float) x, (float) y);
            if (!isPointerDown(pointerId)) return 0;
            return downTime;
        } catch (Exception e) {
            Log.e(TAG, "simulateDown failed", e);
            return 0;
        }
    }

    public static long simulateDown(final int x, final int y) {
        return simulateDown(0, x, y);
    }

    public static void simulateMove(final long token, final int pointerId, final int x, final int y) {
        injectSyncState(pointerId, MotionEvent.ACTION_MOVE, (float) x, (float) y);
    }

    public static void simulateMove(final long token, final int x, final int y) {
        simulateMove(token, 0, x, y);
    }

    // ขยับนิ้วด้วยพิกัดทศนิยม (ละเอียดกว่าปัดเป็นพิกเซลเต็ม ทำให้การลากช้าๆ นิ่งและไม่เป็นขั้นบันได)
    public static void simulateMoveF(final long token, final int pointerId, final float x, final float y) {
        injectSyncState(pointerId, MotionEvent.ACTION_MOVE, x, y);
    }

    public static void simulateUp(final long token, final int pointerId, final int x, final int y) {
        injectSyncState(pointerId, MotionEvent.ACTION_UP, (float) x, (float) y);
    }

    public static void simulateUp(final long token, final int x, final int y) {
        simulateUp(token, 0, x, y);
    }

    // [เพิ่ม] ปุ่มแตะแบบกดค้างตามนิ้ว/ปุ่มคีย์บอร์ดของผู้ใช้
    //   simulatePress(id, x, y) : วางนิ้วลงแล้วค้างไว้ ไม่ปล่อยเอง
    //   simulateRelease(id)     : ปล่อยนิ้วที่ค้างอยู่ (ใช้พิกัดที่กดไว้)
    //   isHeld(id)              : pointer นี้ยังกดค้างอยู่ไหม
    public static boolean isHeld(int pointerId) {
        return activePointers.containsKey(pointerId);
    }

    public static void simulatePress(final int pointerId, final int x, final int y) {
        if (activePointers.containsKey(pointerId)) return; // กันกดซ้ำตอนคีย์บอร์ด repeat
        if (!accessOk()) {
            Log.w(TAG, "simulatePress: ไม่มีทางส่ง event (shell server ไม่เชื่อม และ Shizuku ไม่มีสิทธิ์)");
            return;
        }
        injectSyncState(pointerId, MotionEvent.ACTION_DOWN, (float) x, (float) y);
    }

    public static void simulateRelease(final int pointerId) {
        final PointerState s = activePointers.get(pointerId);
        if (s != null) {
            injectSyncState(pointerId, MotionEvent.ACTION_UP, s.x, s.y);
        }
    }

    public static void simulateClick(final int pointerId, final int x, final int y) {
        try {
            if (!accessOk()) {
                Log.w(TAG, "simulateClick: ไม่มีทางส่ง event (shell server ไม่เชื่อม และ Shizuku ไม่มีสิทธิ์)");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "simulateClick failed", e);
            return;
        }
        injectSyncState(pointerId, MotionEvent.ACTION_DOWN, (float) x, (float) y);
        final PointerState pressed = activePointers.get(pointerId);
        if (pressed == null) return;
        // ค้างนิ้วสั้นๆ ให้เฟรมเกมรับ DOWN ทัน (ปรับที่ CLICK_HOLD_MS)
        clickHandler.postDelayed(() -> {
            if (activePointers.get(pointerId) == pressed) {
                injectSyncState(pointerId, MotionEvent.ACTION_UP, (float) x, (float) y);
            }
        }, CLICK_HOLD_MS);
    }

    public static void simulateClick(final int x, final int y) {
        simulateClick(0, x, y);
    }

    public static void releaseTouch() {
        injectSyncState(0, MotionEvent.ACTION_CANCEL, 0f, 0f);
    }

    public static void releaseAllKeys() {
        releaseTouch();
    }
}
