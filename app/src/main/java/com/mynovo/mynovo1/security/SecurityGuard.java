package com.mynovo.mynovo1.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * รวมจุดตรวจสอบความปลอดภัยของแอป
 *
 * แนวคิด: ไม่มี check เดียวที่ "เด็ดขาด" ทุกตัวหลอกได้ทั้งนั้น
 * เป้าหมายคือทำให้คนแก้ไขต้องเจอหลายด่าน แต่ละด่านเงียบ ไม่ throw/log ชัดเจน
 * เพื่อไม่ให้หา breakpoint ได้ง่าย ๆ จากจุดเดียว
 *
 * สำคัญ: เช็คพวกนี้ "ช่วยชะลอ" ไม่ใช่ป้องกันเด็ดขาด คนที่มีความรู้มากพอ
 * ยังหาทาง patch ออกได้เสมอ ของจริงที่กันได้คือ Play Integrity API (server-side)
 */
public final class SecurityGuard {

    private SecurityGuard() {}

    // ---------- 1. Root detection ----------

    private static final String[] ROOT_PATHS = {
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup",
            "/system/xbin/mu"
    };

    private static final String[] ROOT_PACKAGES = {
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.noshufou.android.su",
            "com.koushikdutta.superuser",
            "com.zachspong.temprootremovejb",
            "com.ramdroid.appquarantine",
            "com.devadvance.rootcloak",
            "com.saurik.substrate"
    };

    public static boolean isDeviceRooted(Context context) {
        return checkRootPaths()
                || checkRootPackages(context)
                || checkTestKeys()
                || checkSuExists()
                || checkWritableSystem();
    }

    private static boolean checkRootPaths() {
        try {
            for (String path : ROOT_PATHS) {
                if (new File(path).exists()) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkRootPackages(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                // ไม่เจอ ถือว่าปกติ เช็คตัวถัดไป
            }
        }
        return false;
    }

    private static boolean checkTestKeys() {
        String tags = Build.TAGS;
        return tags != null && tags.contains("test-keys");
    }

    private static boolean checkSuExists() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            return output != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static boolean checkWritableSystem() {
        try {
            return new File("/system").canWrite() || new File("/system/bin").canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 2. Debugger / Frida / Xposed detection ----------

    public static boolean isDebuggerAttached() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    /** หา port ที่ Frida server มักเปิด (27042 เป็นค่า default) */
    public static boolean isFridaDetected() {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", 27042), 200);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** เช็คกระบวนการที่ชื่อขึ้นต้นด้วย fridaserver/frida-server ผ่าน /proc */
    public static boolean isFridaProcessRunning() {
        try {
            File procDir = new File("/proc");
            File[] dirs = procDir.listFiles();
            if (dirs == null) return false;
            for (File dir : dirs) {
                try {
                    File cmdline = new File(dir, "cmdline");
                    if (cmdline.exists()) {
                        String content = readFile(cmdline);
                        if (content.contains("frida") || content.contains("gum-js-loop")) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readFile(File file) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file)));
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            sb.append((char) c);
        }
        reader.close();
        return sb.toString();
    }

    public static boolean isXposedDetected() {
        try {
            throw new Exception();
        } catch (Exception e) {
            for (StackTraceElement element : e.getStackTrace()) {
                if (element.getClassName().contains("de.robv.android.xposed")) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---------- 3. Emulator detection ----------

    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk".equals(Build.PRODUCT);
    }

    // ---------- 4. Signature verification ----------

    /**
     * ใส่ SHA-256 ของ signing certificate ที่ build release จริงตรงนี้
     * วิธีหา: ./gradlew signingReport แล้วดูค่า SHA-256 ของ release key
     */
    private static final String EXPECTED_SIGNATURE_SHA256 = "PUT_YOUR_RELEASE_CERT_SHA256_HERE";

    @SuppressWarnings("deprecation")
    public static boolean isSignatureValid(Context context) {
        try {
            Signature[] signatures;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (packageInfo.signingInfo == null) return false;
                signatures = packageInfo.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                signatures = packageInfo.signatures;
            }

            if (signatures == null) return false;

            for (Signature signature : signatures) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(signature.toByteArray());
                StringBuilder hashHex = new StringBuilder();
                for (byte b : hash) {
                    hashHex.append(String.format("%02X", b));
                    hashHex.append(":");
                }
                if (hashHex.length() > 0) hashHex.setLength(hashHex.length() - 1);

                if (hashHex.toString().equalsIgnoreCase(EXPECTED_SIGNATURE_SHA256)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** เช็คว่าแอปติดตั้งมาจาก Play Store จริงหรือไม่ (ไม่ใช่ sideload) */
    @SuppressWarnings("deprecation")
    public static boolean isInstalledFromPlayStore(Context context) {
        Set<String> validInstallers = new HashSet<>(Arrays.asList(
                "com.android.vending", "com.google.android.feedback"));

        String installer = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installer = context.getPackageManager()
                        .getInstallSourceInfo(context.getPackageName())
                        .getInstallingPackageName();
            } else {
                installer = context.getPackageManager()
                        .getInstallerPackageName(context.getPackageName());
            }
        } catch (Exception e) {
            // ปล่อยเป็น null
        }

        return installer != null && validInstallers.contains(installer);
    }

    // ---------- 5. รวมผลตรวจสอบ ----------

    public static SecurityCheckResult runSecurityChecks(Context context, boolean allowRooted) {
        boolean rooted = isDeviceRooted(context);
        boolean debugger = isDebuggerAttached();
        boolean frida = isFridaDetected() || isFridaProcessRunning();
        boolean xposed = isXposedDetected();
        boolean validSig = isSignatureValid(context);

        boolean tampered = debugger || frida || xposed || !validSig || (rooted && !allowRooted);

        return new SecurityCheckResult(rooted, debugger, frida, xposed, validSig, tampered);
    }

    public static SecurityCheckResult runSecurityChecks(Context context) {
        return runSecurityChecks(context, false);
    }

    public static final class SecurityCheckResult {
        public final boolean rooted;
        public final boolean debuggerAttached;
        public final boolean fridaDetected;
        public final boolean xposedDetected;
        public final boolean signatureValid;
        public final boolean isTampered;

        public SecurityCheckResult(boolean rooted, boolean debuggerAttached, boolean fridaDetected,
                                    boolean xposedDetected, boolean signatureValid, boolean isTampered) {
            this.rooted = rooted;
            this.debuggerAttached = debuggerAttached;
            this.fridaDetected = fridaDetected;
            this.xposedDetected = xposedDetected;
            this.signatureValid = signatureValid;
            this.isTampered = isTampered;
        }
    }
}
