package com.mynovo.mynovo1;

import android.os.IBinder;
import android.os.Parcel;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * เซิร์ฟเวอร์ที่รันด้วยสิทธิ์ shell (UID 2000) เริ่มจาก activate.sh ด้วย app_process
 * รับ MotionEvent จากแอปผ่าน TCP localhost แล้ว inject เข้าระบบ
 *
 * ห้ามอ้างถึงคลาสอื่นของแอปที่ต้องใช้ Context (ไม่มี Context ในโปรเซสนี้)
 * ถ้าเปิด minify/R8 ต้องมี: -keep class com.mynovo.mynovo1.InputServer { *; }
 */
public class InputServer {
    public static final int PORT = 38417;

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
        final String token = readToken(args[0]);
        if (token.isEmpty()) {
            System.out.println("token file is empty or missing: " + args[0]);
            System.exit(2);
        }

        // เอา IInputManager ตรงๆ (โปรเซสนี้เป็น shell อยู่แล้ว ไม่ต้องผ่าน Shizuku)
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) smClass.getMethod("getService", String.class).invoke(null, "input");
        if (binder == null) {
            System.out.println("input service not found");
            System.exit(3);
        }
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        final Object im = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        final Method inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);

        ServerSocket ss;
        try {
            ss = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
        } catch (BindException e) {
            System.out.println("already running (port " + PORT + " in use)");
            return;
        }
        System.out.println("InputServer started on 127.0.0.1:" + PORT + " uid=" + android.os.Process.myUid());

        while (true) {
            try (Socket s = ss.accept()) {
                s.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 8192));

                String got = in.readUTF();
                if (!token.equals(got)) {
                    s.getOutputStream().write(0);
                    s.getOutputStream().flush();
                    continue;
                }
                s.getOutputStream().write(1);
                s.getOutputStream().flush();
                System.out.println("client connected");

                while (true) {
                    int len = in.readInt();
                    if (len == -1) {
                        // คำสั่งปิดเซิร์ฟเวอร์จากแอป (ปุ่ม Deactivate)
                        System.out.println("stop requested, exiting");
                        System.out.flush();
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
                        try {
                            inject.invoke(im, ev, 0); // 0 = ASYNC
                        } finally {
                            ev.recycle();
                        }
                    } catch (Throwable t) {
                        System.out.println("inject error: " + t);
                    } finally {
                        p.recycle();
                    }
                }
            } catch (EOFException | SocketException e) {
                // แอปปิดการเชื่อมต่อ รอตัวใหม่
            } catch (IOException e) {
                System.out.println("io error: " + e);
            } catch (Throwable t) {
                System.out.println("error: " + t);
            }
            System.out.println("client disconnected");
        }
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
