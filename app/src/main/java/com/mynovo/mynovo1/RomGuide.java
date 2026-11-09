package com.mynovo.mynovo1;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.Locale;

/**
 * บอกผู้ใช้ให้เปิดสวิตช์ของ ROM ที่บล็อกการ inject ทัช
 * ชื่อเมนูต่างกันตามยี่ห้อ/เวอร์ชัน จึงเขียนเป็นคำแนะนำ ไม่ใช่ตัวตรวจสอบแน่นอน
 */
public final class RomGuide {
    private static final String PREF = "MynovoPrefs";
    private static final String KEY_NEED_GUIDE = "needRomGuide";
    private static final String KEY_DISMISSED = "romGuideDismissed";

    public enum Rom { XIAOMI, OPPO, VIVO, HUAWEI, SAMSUNG, OTHER }

    private RomGuide() {}

    public static Rom detect() {
        String s = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) return Rom.XIAOMI;
        if (s.contains("oppo") || s.contains("realme") || s.contains("oneplus")) return Rom.OPPO;
        if (s.contains("vivo") || s.contains("iqoo")) return Rom.VIVO;
        if (s.contains("huawei") || s.contains("honor")) return Rom.HUAWEI;
        if (s.contains("samsung")) return Rom.SAMSUNG;
        return Rom.OTHER;
    }

    public static String steps() {
        switch (detect()) {
            case XIAOMI:
                return "Xiaomi / Redmi / POCO\n\n"
                        + "1. ตั้งค่า > ข้อมูลเกี่ยวกับโทรศัพท์ > กดเลขเวอร์ชัน 7 ครั้ง\n"
                        + "2. เข้า ตัวเลือกสำหรับนักพัฒนา\n"
                        + "3. เปิด \"USB debugging (Security settings)\"\n"
                        + "   (บางรุ่นต้องใส่ซิมและล็อกอิน Mi Account ก่อนถึงจะเปิดได้)\n"
                        + "4. ถ้ามี \"MIUI optimization\" ให้ลองปิด แล้วรีสตาร์ทเครื่อง\n"
                        + "5. กลับมาเปิดแอปแล้วลองใหม่";
            case OPPO:
                return "OPPO / Realme / OnePlus\n\n"
                        + "1. ตั้งค่า > เกี่ยวกับอุปกรณ์ > กดเลขเวอร์ชัน 7 ครั้ง\n"
                        + "2. เข้า ตัวเลือกสำหรับนักพัฒนา\n"
                        + "3. เปิด \"USB debugging\" และ \"USB debugging (Security settings)\" ถ้ามี\n"
                        + "4. ปิด \"Disable permission monitoring\" / \"ปิดการตรวจสอบสิทธิ์\" ถ้ามีตัวเลือกนี้\n"
                        + "5. รีสตาร์ทเครื่องแล้วลองใหม่";
            case VIVO:
                return "vivo / iQOO\n\n"
                        + "1. ตั้งค่า > เกี่ยวกับโทรศัพท์ > เวอร์ชันซอฟต์แวร์ > กดเลขเวอร์ชัน 7 ครั้ง\n"
                        + "2. เข้า ตัวเลือกสำหรับนักพัฒนา\n"
                        + "3. เปิด \"USB debugging\" และ \"USB debugging (Security settings)\" ถ้ามี\n"
                        + "4. รีสตาร์ทเครื่องแล้วลองใหม่";
            case HUAWEI:
                return "Huawei / Honor\n\n"
                        + "1. ตั้งค่า > เกี่ยวกับโทรศัพท์ > กดเลขบิลด์ 7 ครั้ง\n"
                        + "2. เข้า ตัวเลือกสำหรับนักพัฒนา เปิด USB debugging\n"
                        + "3. รีสตาร์ทเครื่องแล้วลองใหม่";
            default:
                return "1. เปิด ตัวเลือกสำหรับนักพัฒนา (กดเลขเวอร์ชัน 7 ครั้ง)\n"
                        + "2. เปิด USB debugging และตัวเลือกที่ชื่อมี \"Security settings\" ถ้ามี\n"
                        + "3. รีสตาร์ทเครื่องแล้วลองใหม่";
        }
    }

    /** ชื่อเมนูอาจต่างตามเวอร์ชัน ถ้าไม่เจอให้ค้นหาคำว่า debugging ในหน้าตัวเลือกนักพัฒนา */
    public static void openDeveloperOptions(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored) {}
        }
    }

    // ===== เรียกจากฝั่ง inject (อาจเป็นเธรดไหนก็ได้) =====
    public static void markNeeded(Context c, String reason) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_NEED_GUIDE, true).apply();
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> Toast.makeText(app,
                "ส่งทัชไม่ได้ (" + reason + ") เปิดแอปเพื่อดูวิธีแก้", Toast.LENGTH_LONG).show());
    }

    // ===== เรียกจาก MainActivity.onResume =====
    public static void showIfNeeded(final Activity a) {
        SharedPreferences sp = a.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!sp.getBoolean(KEY_NEED_GUIDE, false)) return;
        sp.edit().putBoolean(KEY_NEED_GUIDE, false).apply();
        show(a);
    }

    public static void show(final Activity a) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a)
                .setTitle("เครื่องนี้อาจบล็อกการส่งทัช")
                .setMessage(steps() + "\n\nชื่อเมนูอาจต่างกันตามรุ่น ถ้าไม่เจอให้ค้นหาคำว่า \"debugging\"")
                .setPositiveButton("เปิดตัวเลือกนักพัฒนา", (d, w) -> openDeveloperOptions(a))
                .setNegativeButton("ปิด", null)
                .show();
    }
}
