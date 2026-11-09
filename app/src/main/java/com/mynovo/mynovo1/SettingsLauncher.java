package com.mynovo.mynovo1;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

/**
 * เปิดหน้าตั้งค่า (Developer options / Wireless debugging) เป็น "หน้าต่างแยก" ข้างแอป
 * ใช้ตอนกดปุ่ม PAIR แทน startActivity ธรรมดา ไม่แตะ UI ของแอป
 *
 * วิธีใช้: ในตัวจัดการปุ่ม PAIR ให้เรียก
 *     SettingsLauncher.openWirelessDebuggingBeside(context);
 */
public class SettingsLauncher {

    private static final String TAG = "SettingsLauncher";

    public static void openWirelessDebuggingBeside(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        // NEW_TASK = แยกเป็นงานใหม่ (ไม่ทับหน้าต่างแอป), LAUNCH_ADJACENT = ขอให้อยู่ข้างกัน (split / freeform)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        // ให้หน้า Developer options ไฮไลต์แถว Wireless debugging
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        i.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        i.putExtra(":settings:show_fragment_args", args);

        Bundle opts = null;
        try {
            Display d = displayOf(ctx);
            ActivityOptions ao = ActivityOptions.makeBasic();
            // เปิดบนจอเดียวกับแอป (จอ DeX ถ้าแอปอยู่บน DeX)
            ao.setLaunchDisplayId(d.getDisplayId());
            // ครึ่งขวาของจอ ให้ไม่บังหน้าต่างแอปที่อยู่ซ้าย
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);
            Rect r = new Rect(Math.round(dm.widthPixels * 0.52f), Math.round(dm.heightPixels * 0.08f),
                    dm.widthPixels, Math.round(dm.heightPixels * 0.95f));
            ao.setLaunchBounds(r);
            opts = ao.toBundle();
        } catch (Throwable t) {
            Log.w(TAG, "launch options failed: " + t);
        }

        try {
            if (opts != null) ctx.startActivity(i, opts);
            else ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "start with options failed, retry plain: " + t);
            try { ctx.startActivity(i); } catch (Throwable t2) { Log.e(TAG, "startActivity failed", t2); }
        }
    }

    @SuppressWarnings("deprecation")
    static Display displayOf(Context ctx) {
        if (ctx instanceof Activity) {
            return ((Activity) ctx).getWindowManager().getDefaultDisplay();
        }
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }
}
