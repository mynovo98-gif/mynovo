package com.mynovo.mynovo1;

import android.os.IBinder;
import android.os.Parcel;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;

/**
 * เซิร์ฟเวอร์ที่รันด้วยสิทธิ์ shell (UID 2000) เริ่มจาก activate.sh ด้วย app_process
 * รับ MotionEvent จากแอปผ่าน TCP localhost แล้ว inject เข้าระบบ
 *
 * ห้ามอ้างถึงคลาสอื่นของแอปที่ต้องใช้ Context (ไม่มี Context ในโปรเซสนี้)
 * ถ้าเปิด minify/R8 ต้องมี: -keep class com.mynovo.mynovo1.InputServer { *; }
 *
 * แก้จากเวอร์ชันเดิม:
 *  1) แต่ละการเชื่อมต่อใช้เธรดของตัวเอง และการเชื่อมต่อใหม่จะ "แทนที่" ของเก่าทันที
 *  2) ถ้า client หลุดหรือถูกแทนที่ระหว่างที่นิ้วยังกดอยู่ จะ inject ACTION_CANCEL ให้
 *  3) ถ้าได้ ACTION_DOWN ซ้อนตอนที่นิ้วเก่ายังไม่ปล่อย จะ CANCEL ของเก่าก่อน
 *  4) handshake มี timeout กันการเชื่อมต่อเปล่าบล็อก server
 *  5) แยกเธรด inject เฉพาะ (coalesce เฉพาะ MOVE ติดกันของ client เดียวกัน)
 *
 * [KB] ระบบจับคีย์บอร์ด/เมาส์แบบ exclusive (EVIOCGRAB):
 *  - args[1] (ไม่บังคับ) = path ของตัวช่วย native (/data/local/tmp/mynovo_kbgrab)
 *  - แอป -> server: writeInt(-2) = เริ่ม grab, writeInt(-3) = ปล่อย grab (-1 = สั่งปิด server)
 *  - [DISPLAY] แอป -> server: writeInt(-4) ตามด้วย writeInt(displayId)
 *      = บังคับ displayId ให้ทุก MotionEvent ก่อน inject (-1 = เลิกบังคับ)
 *      ทำที่ server เพราะรันสิทธิ์ shell เรียก InputEvent.setDisplayId (hidden API) ได้
 *      ส่วนฝั่งแอปบางเครื่อง/บางเวอร์ชัน Android โดนบล็อก
 *  - server -> แอป: เฟรมละ 9 ไบต์ = [type:1][a:int][b:int]
 *      type 1 = ปุ่มคีย์บอร์ด  a = Linux scancode, b = 0 ปล่อย / 1 กด / 2 ค้าง
 *      type 2 = ผู้ใช้กด Ctrl+Esc ปล่อย grab (ทางหนีไฟ)
 *      type 3 = จำนวนคีย์บอร์ดที่ grab อยู่ (a = จำนวน, 0 = ไม่สำเร็จ)
 *      type 4 = [MOUSE] เมาส์เลื่อน  a = dx, b = dy (ส่งเฉพาะตอน grab อยู่)
 *      type 5 = [MOUSE] ปุ่มเมาส์    a = Linux code (BTN_LEFT=272, BTN_RIGHT=273, ...), b = 0 ปล่อย / 1 กด
 *  - ถ้า client หลุด/ถูกแทนที่ จะปล่อย grab ให้อัตโนมัติ คีย์บอร์ดจึงไม่ค้างตาย
 */
public class InputServer {
    public static final int PORT = 38417;

    private static final boolean LOG_INJECT_TIMING = true;
    private static final long LOG_SLOW_INJECT_MS = 3L;
    private static final int QUEUE_WARN_SIZE = 5;

    private static final Object injectLock = new Object();
    private static Object im;
    private static Method inject;
    private static String token;

    // ทั้งหมดนี้เข้าถึงภายใน synchronized (injectLock) เท่านั้น
    private static Socket current;
    private static MotionEvent lastEvent;
    private static boolean active;

    // [DISPLAY] displayId ที่บังคับใช้ (-1 = ไม่บังคับ ใช้ค่าตามที่ event มาจากแอป)
    private static volatile int forcedDisplay = -1;
    private static Method setDispMethod;
    private static boolean setDispResolved = false;
    private static boolean setDispWarned = false;

    // ===== คิว inject แยกออกจาก thread อ่าน socket =====
    private static final class QueuedEvent {
        final Socket socket;
        final MotionEvent event;
        QueuedEvent(Socket s, MotionEvent e) { socket = s; event = e; }
    }
    private static final Object queueLock = new Object();
    private static final ArrayDeque<QueuedEvent> pendingInject = new ArrayDeque<>();
    private static final Thread injectWorker = new Thread(InputServer::injectWorkerLoop, "inject-worker");
    static {
        injectWorker.setDaemon(true);
        try { injectWorker.setPriority(Thread.MAX_PRIORITY); } catch (Throwable ignored) {}
        injectWorker.start();
    }

    private static void enqueueInject(Socket s, MotionEvent ev) {
        synchronized (queueLock) {
            QueuedEvent last = pendingInject.peekLast();
            if (last != null && last.socket == s
                    && last.event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                pendingInject.pollLast();
                last.event.recycle();
            }
            pendingInject.addLast(new QueuedEvent(s, ev));
            int size = pendingInject.size();
            queueLock.notifyAll();
            if (size >= QUEUE_WARN_SIZE) {
                System.out.println("inject queue backlog: " + size + " pending");
            }
        }
    }

    private static void injectWorkerLoop() {
        while (true) {
            QueuedEvent qe;
            synchronized (queueLock) {
                while (pendingInject.isEmpty()) {
                    try { queueLock.wait(); } catch (InterruptedException ignored) {}
                }
                qe = pendingInject.pollFirst();
            }
            try {
                synchronized (injectLock) {
                    if (current == qe.socket) injectTracked(qe.event);
                }
            } finally {
                qe.event.recycle();
            }
        }
    }

    // ===== [KB] จับคีย์บอร์ด =====
    private static String kbPath;
    private static final Object kbLock = new Object();
    private static Process kbProc;          // เข้าถึงใน synchronized (kbLock)
    private static OutputStream kbIn;       // stdin ของตัวช่วย
    private static final Object outLock = new Object();
    private static DataOutputStream currentOut; // ท่อส่งกลับไปหา client ปัจจุบัน
    private static Socket outSocket;

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
            System.out.println("usage: InputServer <tokenFile> [kbGrabHelperPath]");
            System.exit(2);
        }
        token = readToken(args[0]);
        if (token.isEmpty()) {
            System.out.println("token file is empty or missing: " + args[0]);
            System.exit(2);
        }
        if (args.length >= 2 && args[1] != null && !args[1].isEmpty()) {
            kbPath = args[1];
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
        System.out.println("InputServer started on 127.0.0.1:" + PORT + " uid=" + android.os.Process.myUid()
                + " kbHelper=" + kbPath);

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
            // [KB] client ใหม่เริ่มจากสถานะไม่ grab (ถ้าอยาก grab ให้ส่ง -2 มาเอง)
            kbSetGrab(false);
            // [DISPLAY] client ใหม่เริ่มจากไม่บังคับ displayId (ถ้าต้องการให้ส่ง -4 มาเอง)
            forcedDisplay = -1;
            s.setSoTimeout(0);
            s.getOutputStream().write(1);
            s.getOutputStream().flush();
            // หลัง handshake เสร็จค่อยเปิดท่อส่งเฟรมกลับไปหา client
            synchronized (outLock) {
                currentOut = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 256));
                outSocket = s;
            }
            System.out.println("client connected");

            while (true) {
                int len = in.readInt();
                if (len == -1) {
                    System.out.println("stop requested, exiting");
                    System.out.flush();
                    synchronized (injectLock) { cancelActive(); }
                    kbShutdown(); // [KB]
                    System.exit(0);
                }
                // [KB] -2 = เริ่ม grab คีย์บอร์ด, -3 = ปล่อย grab (รับเฉพาะจาก client ปัจจุบัน)
                if (len == -2 || len == -3) {
                    boolean stale0;
                    synchronized (injectLock) { stale0 = current != s; }
                    if (stale0) break;
                    kbSetGrab(len == -2);
                    continue;
                }
                // [DISPLAY] -4 <displayId> = บังคับ displayId (รับเฉพาะจาก client ปัจจุบัน)
                if (len == -4) {
                    int id = in.readInt();
                    boolean stale1;
                    synchronized (injectLock) { stale1 = current != s; }
                    if (stale1) break;
                    synchronized (injectLock) {
                        // เปลี่ยนจอกลางทาง: ปล่อยนิ้วที่ค้างบนจอเดิมก่อน
                        if (id != forcedDisplay) cancelActive();
                        forcedDisplay = id;
                    }
                    System.out.println("forced displayId=" + id);
                    continue;
                }
                if (len <= 0 || len > 65536) break;
                byte[] buf = new byte[len];
                in.readFully(buf);

                Parcel p = Parcel.obtain();
                try {
                    p.unmarshall(buf, 0, len);
                    p.setDataPosition(0);
                    MotionEvent ev = MotionEvent.CREATOR.createFromParcel(p);
                    enqueueInject(s, ev);
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
            boolean wasCurrent = false;
            synchronized (injectLock) {
                if (current == s) {
                    current = null;
                    cancelActive(); // ปล่อยนิ้วที่ค้าง
                    wasCurrent = true;
                }
            }
            // [KB] client หลุด -> ปิดท่อส่งกลับ และปล่อย grab คืนให้ระบบ
            synchronized (outLock) {
                if (outSocket == s) {
                    currentOut = null;
                    outSocket = null;
                }
            }
            if (wasCurrent) kbSetGrab(false);
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
            lastEvent = MotionEvent.obtain(ev); // สำเนานี้ติด displayId ที่ตั้งใน doInject ไปด้วย
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

    // [DISPLAY] ตั้ง displayId ให้ event ถ้ามีการบังคับไว้ (เรียกภายใน injectLock)
    private static void applyForcedDisplay(MotionEvent ev) {
        final int id = forcedDisplay;
        if (id < 0) return;
        try {
            if (!setDispResolved) {
                setDispResolved = true;
                try {
                    setDispMethod = InputEvent.class.getMethod("setDisplayId", int.class);
                } catch (Throwable t) {
                    setDispMethod = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                }
                setDispMethod.setAccessible(true);
            }
            if (setDispMethod != null) setDispMethod.invoke(ev, id);
        } catch (Throwable t) {
            if (!setDispWarned) {
                setDispWarned = true;
                System.out.println("setDisplayId failed: " + t);
            }
        }
    }

    private static void doInject(MotionEvent ev) {
        final long t0 = LOG_INJECT_TIMING ? System.nanoTime() : 0L;
        try {
            applyForcedDisplay(ev); // [DISPLAY]
            inject.invoke(im, ev, 0); // 0 = ASYNC
        } catch (Throwable t) {
            System.out.println("inject error: " + t);
        } finally {
            if (LOG_INJECT_TIMING) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms >= LOG_SLOW_INJECT_MS) {
                    System.out.println("inject slow (binder call): " + ms + "ms action="
                            + MotionEvent.actionToString(ev.getActionMasked()));
                }
            }
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

    // ===================== [KB] จับคีย์บอร์ด/เมาส์แบบ exclusive =====================

    // ส่งเฟรม 9 ไบต์กลับไปหา client ปัจจุบัน (ถ้าไม่มี client ก็ทิ้ง)
    private static void sendToClient(int type, int a, int b) {
        synchronized (outLock) {
            if (currentOut == null) return;
            try {
                currentOut.writeByte(type);
                currentOut.writeInt(a);
                currentOut.writeInt(b);
                currentOut.flush();
            } catch (IOException e) {
                currentOut = null;
                outSocket = null;
            }
        }
    }

    // เปิด/ปิด grab: เริ่มตัวช่วยตอนเปิดครั้งแรก แล้วส่งคำสั่งทาง stdin ('G' / 'U')
    private static void kbSetGrab(boolean on) {
        synchronized (kbLock) {
            try {
                if (kbProc == null) {
                    if (!on) return;
                    if (!kbStartHelper()) {
                        sendToClient(3, 0, 0); // grab ไม่สำเร็จ แอปจะใช้ทางเดิมต่อไป
                        return;
                    }
                }
                kbIn.write(on ? 'G' : 'U');
                kbIn.flush();
            } catch (IOException e) {
                System.out.println("kb write error: " + e);
                kbKillLocked();
                if (on) sendToClient(3, 0, 0);
            }
        }
    }

    // เรียกภายใน kbLock
    private static boolean kbStartHelper() {
        try {
            if (kbPath == null || !new File(kbPath).canExecute()) {
                System.out.println("kb helper not available: " + kbPath);
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(kbPath);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            final Process p = pb.start();
            kbProc = p;
            kbIn = p.getOutputStream();
            Thread t = new Thread(() -> kbReadLoop(p), "kb-reader");
            t.setDaemon(true);
            t.start();
            System.out.println("kb helper started");
            return true;
        } catch (Throwable t) {
            System.out.println("kb helper start failed: " + t);
            kbProc = null;
            kbIn = null;
            return false;
        }
    }

    // อ่านข้อความจากตัวช่วยทีละบรรทัดแล้วส่งต่อให้แอปเป็นเฟรม
    private static void kbReadLoop(Process p) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = br.readLine()) != null) {
                if (l.isEmpty()) continue;
                char c = l.charAt(0);
                try {
                    if (c == 'K') {
                        String[] a = l.split(" ");
                        if (a.length >= 3) {
                            sendToClient(1, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                        }
                    } else if (c == 'R') {
                        sendToClient(2, 0, 0);
                    } else if (c == 'S') {
                        String[] a = l.split(" ");
                        sendToClient(3, a.length >= 2 ? Integer.parseInt(a[1]) : 0, 0);
                    } else if (c == 'M') {
                        // [MOUSE] เมาส์เลื่อน: M <dx> <dy> -> type 4
                        String[] a = l.split(" ");
                        if (a.length >= 3) {
                            sendToClient(4, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                        }
                    } else if (c == 'B') {
                        // [MOUSE] ปุ่มเมาส์: B <code> <v> -> type 5
                        String[] a = l.split(" ");
                        if (a.length >= 3) {
                            sendToClient(5, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                        }
                    } else if (c == 'H') {
                        System.out.println("kb helper: " + l);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        synchronized (kbLock) {
            if (kbProc == p) {
                kbProc = null;
                kbIn = null;
            }
        }
        try { p.destroy(); } catch (Throwable ignored) { }
        sendToClient(3, 0, 0); // ตัวช่วยตาย = ไม่มี grab แล้ว
    }

    // เรียกภายใน kbLock
    private static void kbKillLocked() {
        Process p = kbProc;
        kbProc = null;
        kbIn = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    private static void kbShutdown() {
        synchronized (kbLock) {
            if (kbIn != null) {
                try {
                    kbIn.write('Q');
                    kbIn.flush();
                } catch (IOException ignored) { }
            }
            kbKillLocked();
        }
    }
}
