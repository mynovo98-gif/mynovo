package com.mynovo.mynovo1;

import android.app.Activity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

public class ShellActivationDialog {

    public static void show(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("⌨️ Quick Activation (Command)")
                .setMessage("1. รันคำสั่ง activate (activate.sh) จากเทอร์มินัลหรือ ADB\n"
                        + "2. กลับมาที่หน้านี้แล้วกด CONNECT")
                .setPositiveButton("CONNECT", (d, w) ->
                        ShellServerClient.connectAsync(ok -> activity.runOnUiThread(() ->
                                Toast.makeText(activity,
                                        ok ? "✅ Connected" : "❌ Connection not found",
                                        Toast.LENGTH_SHORT).show())))
                .setNegativeButton("Close", null)
                .show();
    }
}