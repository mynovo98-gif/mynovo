package com.mynovo.mynovo1;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ฝั่งแอป: เชื่อม InputServer (ที่เปิดด้วย activate.sh) แล้วส่ง MotionEvent ไปให้ inject
 * การ connect ทำนอก main thread เสมอ (Android ห้ามใช้ socket บน main thread)
 * ต้องมี <uses-permission android:name="android.permission.INTERNET"/> ใน AndroidManifest
 */
public final class ShellServerClient {
    private static final String TAG = "ShellServerClient";
    private static final long RETRY_INTERVAL_MS = 2000L;

    public interface Callback {
        void onResult(boolean connected);
    }

    private static final Object lock = new Object();
    private static final Object connectLock = new Object();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ShellServerConnect");
        t.setDaemon(true);
        return t;
    });

    private static Socket socket;
    private static DataOutputStream out;
    private static volatile Context appContext;
    private static volatile long lastTryUptime = 0;

    private ShellServerClient() {}

    public static void setContext(Context c) {
        if (c != null) appContext = c.getApplicationContext();
    }

    public static boolean isConnected() {
        synchronized (lock) {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }
    }

    // เรียกได้ถี่ๆ (มี throttle 2 วินาที) ใช้ตอน inject แล้วยังไม่เชื่อม
    public static void ensureConnectedAsync() {
        if (isConnected()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTryUptime < RETRY_INTERVAL_MS) return;
        lastTryUptime = now;
        startConnect(null);
    }

    // ใช้กับปุ่ม "ตรวจสอบสถานะ" ในหน้าตั้งค่า (ไม่มี throttle) callback วิ่งบน main thread
    public static void connectAsync(Callback cb) {
        if (isConnected()) {
            if (cb != null) mainHandler.post(() -> cb.onResult(true));
            return;
        }
        lastTryUptime = SystemClock.uptimeMillis();
        startConnect(cb);
    }

    // สั่งปิด InputServer (ปุ่ม Deactivate) callback วิ่งบน main thread: true = ส่งคำสั่งปิดสำเร็จ
    public static void stopServerAsync(final Callback cb) {
        exec.execute(() -> {
            boolean sent = false;
            // รอให้ CANCEL ที่เพิ่งสั่งปล่อยนิ้วในคิวถูกส่งออกไปก่อน ไม่งั้นนิ้วอาจค้างในระบบ
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            if (isConnected() || connectBlocking()) {
                try {
                    synchronized (lock) {
                        if (out != null) {
                            out.writeInt(-1);
                            out.flush();
                            sent = true;
                        }
                    }
                } catch (IOException ignored) {}
            }
            close();
            final boolean result = sent;
            if (cb != null) mainHandler.post(() -> cb.onResult(result));
        });
    }

    private static void startConnect(final Callback cb) {
        exec.execute(() -> {
            boolean ok = connectBlocking();
            if (cb != null) mainHandler.post(() -> cb.onResult(ok));
        });
    }

    private static boolean connectBlocking() {
        synchronized (connectLock) {
            if (isConnected()) return true;
            Context c = appContext;
            if (c == null) return false;
            String token = ShellServerSetup.readToken(c);
            if (token == null) return false;

            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), InputServer.PORT), 300);
                s.setTcpNoDelay(true);
                s.setSoTimeout(1000);
                DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 4096));
                o.writeUTF(token);
                o.flush();
                int r = s.getInputStream().read();
                if (r != 1) {
                    s.close();
                    return false;
                }
                s.setSoTimeout(0);
                synchronized (lock) {
                    closeLocked();
                    socket = s;
                    out = o;
                }
                Log.i(TAG, "connected to shell server");
                return true;
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) {}
                return false;
            }
        }
    }

    // เรียกจากเธรดส่ง event เท่านั้น ส่ง false ถ้าเขียนไม่สำเร็จ (ปิดการเชื่อมต่อให้เอง)
    public static boolean send(MotionEvent ev) {
        Parcel p = Parcel.obtain();
        try {
            ev.writeToParcel(p, 0);
            byte[] b = p.marshall();
            synchronized (lock) {
                if (out == null) return false;
                out.writeInt(b.length);
                out.write(b);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "send failed, closing", e);
            close();
            return false;
        } finally {
            p.recycle();
        }
    }

    public static void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private static void closeLocked() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        out = null;
    }
}
