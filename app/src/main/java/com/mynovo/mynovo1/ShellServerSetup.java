package com.mynovo.mynovo1;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * เตรียมไฟล์สำหรับ "เปิดใช้งานด่วน": สร้าง activate.sh + token ใน
 * /sdcard/Android/data/<แพ็กเกจ>/files/exe/ แล้วให้ผู้ใช้วางคำสั่ง sh .../activate.sh
 * ใน terminal ที่รันด้วยสิทธิ์ shell (adb shell)
 */
public final class ShellServerSetup {
    private ShellServerSetup() {}

    public static File exeDir(Context c) {
        File dir = c.getExternalFilesDir("exe");
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir;
    }

    // เขียน activate.sh (เขียนทับทุกครั้ง) และสร้าง token ถ้ายังไม่มี คืนคำสั่งที่ต้องรัน
    public static String prepare(Context c) throws IOException {
        File dir = exeDir(c);
        if (dir == null) throw new IOException("external files dir not available");

        File script = new File(dir, "activate.sh");
        // [แก้ไข] ส่งตำแหน่งโฟลเดอร์ lib ของแอปเข้าไปด้วย เพื่อให้สคริปต์คัดลอกตัวช่วยจับคีย์บอร์ด (libkbgrab.so)
        writeText(script, buildScript(c.getPackageName(), c.getApplicationInfo().nativeLibraryDir));
        script.setReadable(true, false);

        File token = new File(dir, "token");
        if (!token.exists() || token.length() == 0) {
            writeText(token, randomToken());
        }
        token.setReadable(true, false);
        return "sh " + script.getAbsolutePath();
    }

    public static String getCommand(Context c) {
        File dir = exeDir(c);
        return dir == null ? "" : "sh " + new File(dir, "activate.sh").getAbsolutePath();
    }

    public static void copyCommand(Context c) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("command", getCommand(c)));
    }

    // คืน null ถ้ายังไม่มีไฟล์ token
    static String readToken(Context c) {
        File dir = exeDir(c);
        if (dir == null) return null;
        File f = new File(dir, "token");
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s = r.readLine();
            return (s == null || s.trim().isEmpty()) ? null : s.trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String buildScript(String pkg, String nativeLibDir) {
        String cls = InputServer.class.getName();
        return "#!/system/bin/sh\n"
                + "PKG=" + pkg + "\n"
                + "NATIVE_DIR=\"" + (nativeLibDir == null ? "" : nativeLibDir) + "\"\n"
                + "DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
                + "APK=\"$(pm path $PKG | head -n 1 | cut -d: -f2)\"\n"
                + "if [ -z \"$APK\" ]; then\n"
                + "  echo \"ERROR: package $PKG not found\"\n"
                + "  exit 1\n"
                + "fi\n"
                + "pkill -f " + cls + " >/dev/null 2>&1\n"
                // [เพิ่ม] ปิดตัวช่วยจับคีย์บอร์ดตัวเก่า (ปล่อย grab) ก่อนคัดลอกทับ
                + "pkill -f mynovo_kbgrab >/dev/null 2>&1\n"
                + "sleep 0.3\n"
                // [เพิ่ม] คัดลอกตัวช่วยจับคีย์บอร์ดไปที่ที่ shell รันได้ (หาไม่เจอก็ข้าม ระบบอื่นยังใช้ได้ปกติ)
                + "KB=/data/local/tmp/mynovo_kbgrab\n"
                + "KB_SRC=\"$NATIVE_DIR/libkbgrab.so\"\n"
                + "if [ ! -f \"$KB_SRC\" ]; then\n"
                + "  KB_SRC=\"$(ls \"$(dirname \"$APK\")\"/lib/*/libkbgrab.so 2>/dev/null | head -n 1)\"\n"
                + "fi\n"
                + "rm -f $KB\n"
                + "if [ -n \"$KB_SRC\" ] && [ -f \"$KB_SRC\" ]; then\n"
                + "  cp \"$KB_SRC\" $KB && chmod 755 $KB\n"
                + "else\n"
                + "  echo \"note: libkbgrab.so not found (keyboard grab disabled)\"\n"
                + "fi\n"
                + "LOG=/data/local/tmp/mynovo_server.log\n"
                // </dev/null ตัด stdin ไม่ให้ process ผูกกับ adb stream
                + "CLASSPATH=\"$APK\" setsid nohup app_process /system/bin " + cls
                + " \"$DIR/token\" $KB > $LOG 2>&1 </dev/null &\n"
                + "sleep 1\n"
                + "echo \"started (log: $LOG)\"\n"
                + "head -c 400 $LOG\n";
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f, false)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
