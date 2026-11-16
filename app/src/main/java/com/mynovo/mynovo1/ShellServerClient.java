package com.mynovo.mynovo1;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * ตัวเชื่อมต่อไปยัง InputServer (activate.sh) ผ่าน TCP 127.0.0.1
 *  - แอป -> server : [len:int][MotionEvent parcel]  /  -2 = เริ่ม grab คีย์บอร์ด  /  -3 = ปล่อย grab  /  -1 = ปิด server
 *  - server -> แอป : เฟรมละ 9 ไบต์ [type:1][a:int][b:int]
 *      type 1 = ปุ่มคีย์บอร์ด (a = Linux scancode, b = 0 ปล่อย / 1 กด / 2 ค้าง)
 *      type 2 = ผู้ใช้กด Ctrl+Esc (ทางหนีไฟ ปล่อย grab)
 *      type 3 = จำนวนคีย์บอร์ดที่ grab อยู่ (a)
 *
 * InputServer รับ client ได้ทีละ 1 ตัว (ตัวใหม่แทนตัวเก่า) จึงต้องใช้การเชื่อมต่อเดียวนี้ร่วมกัน
 * ทั้งการส่งทัชและการรับคีย์บอร์ด
 */
public final class ShellServerClient {
    private static final String TAG = "ShellServerClient";
    private static final String HOST = "127.0.0.1";

    public interface KeyListener {
        /** เรียกจากเธรดอ่านของตัวเชื่อมต่อ (ไม่ใช่ main thread) */
        void onKey(int scanCode, int value);
        void onEscape();
        void onGrabState(int grabbedCount);
        void onDisconnected();
    }

    private static final Object lock = new Object();
    private static final Object writeLock = new Object();

    private static Context appCtx;
    private static Socket socket;
    private static DataOutputStream out;
    private static volatile boolean connected = false;
    private static boolean connecting = false;
    private static long lastTry = 0;

    private static volatile KeyListener keyListener;
    private static volatile boolean wantGrab = false;

    // เธรดแยกสำหรับส่งคำสั่ง grab (ห้ามเขียน socket บน main thread)
    private static final HandlerThread cmdThread = new HandlerThread("ShellSrvCmd");
    private static final Handler cmdHandler;
    static {
        cmdThread.start();
        cmdHandler = new Handler(cmdThread.getLooper());
    }

    private ShellServerClient() {}

    public static void setContext(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    public static boolean isConnected() {
        return connected;
    }

    public static void setKeyListener(KeyListener l) {
        keyListener = l;
    }

    public static void ensureConnectedAsync() {
        if (connected) return;
        synchronized (lock) {
            if (connecting) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastTry < 1000) return;
            lastTry = now;
            connecting = true;
        }
        Thread t = new Thread(ShellServerClient::connectBlocking, "ShellSrvConnect");
        t.setDaemon(true);
        t.start();
    }

    private static void connectBlocking() {
        Socket s = null;
        try {
            Context c = appCtx;
            if (c == null) return;
            String token = ShellServerSetup.readToken(c);
            if (token == null) return;

            s = new Socket();
            s.connect(new InetSocketAddress(HOST, InputServer.PORT), 500);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(2000);

            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 4096));
            o.writeUTF(token);
            o.flush();
            int r = s.getInputStream().read();
            if (r != 1) {
                closeQuietly(s);
                return;
            }
            s.setSoTimeout(0);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256));

            synchronized (writeLock) { out = o; }
            synchronized (lock) { socket = s; }
            connected = true;
            Log.i(TAG, "connected to InputServer");

            final Socket fs = s;
            final DataInputStream fin = in;
            Thread rt = new Thread(() -> readLoop(fs, fin), "ShellSrvRead");
            rt.setDaemon(true);
            rt.start();

            // server เริ่มจากสถานะไม่ grab เสมอ ถ้าแอปต้องการ grab อยู่ให้ส่งซ้ำ
            if (wantGrab) writeInt(-2);
        } catch (Throwable t) {
            closeQuietly(s);
        } finally {
            synchronized (lock) { connecting = false; }
        }
    }

    private static void readLoop(Socket s, DataInputStream in) {
        try {
            while (true) {
                int type = in.readUnsignedByte();
                int a = in.readInt();
                int b = in.readInt();
                KeyListener l = keyListener;
                if (l == null) continue;
                if (type == 1) l.onKey(a, b);
                else if (type == 2) l.onEscape();
                else if (type == 3) l.onGrabState(a);
            }
        } catch (Throwable ignored) {
        }
        dropConnection(s);
    }

    private static void dropConnection(Socket s) {
        boolean was = false;
        Socket toClose = null;
        synchronized (lock) {
            if (s != null && socket != s) {
                toClose = s;
            } else {
                was = socket != null;
                toClose = socket;
                socket = null;
                connected = false;
            }
        }
        synchronized (writeLock) {
            if (was) out = null;
        }
        closeQuietly(toClose);
        if (was) {
            Log.i(TAG, "disconnected from InputServer");
            KeyListener l = keyListener;
            if (l != null) l.onDisconnected();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    private static boolean writeInt(int v) {
        try {
            synchronized (writeLock) {
                DataOutputStream o = out;
                if (o == null) return false;
                o.writeInt(v);
                o.flush();
            }
            return true;
        } catch (IOException e) {
            dropConnection(null);
            return false;
        }
    }

    /** ส่ง MotionEvent ไปให้ server inject (เรียกจากเธรดส่ง event ของ TouchSimulator) */
    public static boolean send(MotionEvent ev) {
        if (!connected) return false;
        Parcel p = Parcel.obtain();
        try {
            ev.writeToParcel(p, 0);
            byte[] b = p.marshall();
            synchronized (writeLock) {
                DataOutputStream o = out;
                if (o == null) return false;
                o.writeInt(b.length);
                o.write(b);
                o.flush();
            }
            return true;
        } catch (IOException e) {
            dropConnection(null);
            return false;
        } finally {
            p.recycle();
        }
    }

    /**
     * ขอเริ่ม/ปล่อย grab คีย์บอร์ด จำค่าที่ต้องการไว้ ถ้ายังไม่เชื่อมต่อจะส่งให้เองตอนเชื่อมต่อสำเร็จ
     * @return true ถ้าเชื่อมต่ออยู่และกำลังส่งคำสั่ง
     */
    public static boolean requestKbGrab(boolean on) {
        wantGrab = on;
        if (on && !connected) {
            ensureConnectedAsync();
            return false;
        }
        cmdHandler.post(() -> {
            if (connected) writeInt(wantGrab ? -2 : -3);
        });
        return connected;
    }

    /** สั่งปิด InputServer */
    public static void stopServer() {
        cmdHandler.post(() -> {
            if (connected) writeInt(-1);
        });
    }

    public static void disconnect() {
        dropConnection(null);
    }
}
