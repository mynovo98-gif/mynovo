package com.mynovo.mynovo1;

import android.os.IBinder;
import android.os.Parcel;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * เซิร์ฟเวอร์ที่รันด้วยสิทธิ์ shell (UID 2000) เริ่มจาก activate.sh ด้วย app_process
 * รับ MotionEvent จากแอปผ่าน TCP localhost แล้ว inject เข้าระบบ
 *
 * ห้ามอ้างถึงคลาสอื่นของแอปที่ต้องใช้ Context (ไม่มี Context ในโปรเซสนี้)
 * ถ้าเปิด minify/R8 ต้องมี: -keep class com.mynovo.mynovo1.InputServer { *; }
 *
 * แก้จากเวอร์ชันเดิม:
 *  1) แต่ละการเชื่อมต่อใช้เธรดของตัวเอง และการเชื่อมต่อใหม่จะ "แทนที่" ของเก่าทันที
 *     (เดิมถ้าของเก่าค้างอยู่ ตัวใหม่จะต่อไม่ได้เพราะ server วนอ่านของเก่าอยู่)
 *  2) ถ้า client หลุดหรือถูกแทนที่ระหว่างที่นิ้วยังกดอยู่ จะ inject ACTION_CANCEL ให้
 *     (เดิมนิ้วจะค้างในระบบ ทำให้เกม/หน้าจอเหมือนค้าง)
 *  3) ถ้าได้ ACTION_DOWN ซ้อนตอนที่นิ้วเก่ายังไม่ปล่อย จะ CANCEL ของเก่าก่อน
 *  4) handshake มี timeout กันการเชื่อมต่อเปล่าบล็อก server
 */
public class InputServer {
    public static final int PORT = 38417;

    private static final Object injectLock = new Object();
    private static Object im;
    private static Method inject;
    private static String token;

    // ทั้งหมดนี้เข้าถึงภายใน synchronized (injectLock) เท่านั้น
    private static Socket current;
    private static MotionEvent lastEvent;
    private static boolean active;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("InputServer fatal: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: InputServer <tokenFile>");
            System.exit(2);
        }
        token = readToken(args[0]);
        if (token.isEmpty()) {
            System.out.println("token file is empty or missing: " + args[0]);
            System.exit(2);
        }

        Class<?> smClass = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) smClass.getMethod("getService", String.class).invoke(null, "input");
        if (binder == null) {
            System.out.println("input service not found");
            System.exit(3);
        }
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        im = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);

        ServerSocket ss;
        try {
            ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        } catch (BindException e) {
            System.out.println("already running (port " + PORT + " in use)");
            return;
        }
        System.out.println("InputServer started on 127.0.0.1:" + PORT + " uid=" + android.os.Process.myUid());

        while (true) {
            try {
                final Socket s = ss.accept();
                Thread t = new Thread(() -> handle(s), "client");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                System.out.println("accept error: " + t);
            }
        }
    }

    private static void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(2000); // handshake timeout
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8192));

            String got = in.readUTF();
            if (!token.equals(got)) {
                s.getOutputStream().write(0);
                s.getOutputStream().flush();
                return;
            }

            // ตัวใหม่แทนที่ตัวเก่า: ปิดของเก่า และปล่อยนิ้วที่ค้างอยู่
            synchronized (injectLock) {
                if (current != null) closeQuietly(current);
                cancelActive();
                current = s;
            }
            s.setSoTimeout(0);
            s.getOutputStream().write(1);
            s.getOutputStream().flush();
            System.out.println("client connected");

            while (true) {
                int len = in.readInt();
                if (len == -1) {
                    System.out.println("stop requested, exiting");
                    System.out.flush();
                    synchronized (injectLock) { cancelActive(); }
                    System.exit(0);
                }
                if (len <= 0 || len > 65536) break;
                byte[] buf = new byte[len];
                in.readFully(buf);

                Parcel p = Parcel.obtain();
                try {
                    p.unmarshall(buf, 0, len);
                    p.setDataPosition(0);
                    MotionEvent ev = MotionEvent.CREATOR.createFromParcel(p);
                    boolean stale;
                    try {
                        synchronized (injectLock) {
                            stale = current != s;
                            if (!stale) injectTracked(ev);
                        }
                    } finally {
                        ev.recycle();
                    }
                    if (stale) break;
                } catch (Throwable t) {
                    System.out.println("inject error: " + t);
                } finally {
                    p.recycle();
                }
            }
        } catch (IOException e) {
            // client ปิดการเชื่อมต่อ / timeout / ถูกแทนที่
        } catch (Throwable t) {
            System.out.println("error: " + t);
        } finally {
            synchronized (injectLock) {
                if (current == s) {
                    current = null;
                    cancelActive(); // ปล่อยนิ้วที่ค้าง
                }
            }
            closeQuietly(s);
            System.out.println("client disconnected");
        }
    }

    // เรียกภายใน injectLock
    private static void injectTracked(MotionEvent ev) {
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN && active) {
            cancelActive(); // DOWN ซ้อน: นิ้วเก่าถูกทิ้งไว้ ปล่อยก่อน
        }
        doInject(ev);

        if (act == MotionEvent.ACTION_DOWN) {
            active = true;
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            active = false;
        }

        if (lastEvent != null) {
            lastEvent.recycle();
            lastEvent = null;
        }
        if (active) {
            lastEvent = MotionEvent.obtain(ev);
        }
    }

    // เรียกภายใน injectLock
    private static void cancelActive() {
        if (active && lastEvent != null) {
            MotionEvent c = MotionEvent.obtain(lastEvent);
            try {
                c.setAction(MotionEvent.ACTION_CANCEL);
                doInject(c);
            } finally {
                c.recycle();
            }
        }
        if (lastEvent != null) {
            lastEvent.recycle();
            lastEvent = null;
        }
        active = false;
    }

    private static void doInject(MotionEvent ev) {
        try {
            inject.invoke(im, ev, 0); // 0 = ASYNC
        } catch (Throwable t) {
            System.out.println("inject error: " + t);
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    private static String readToken(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return "";
        }
    }
}
