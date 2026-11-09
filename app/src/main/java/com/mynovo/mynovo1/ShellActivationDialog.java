package com.mynovo.mynovo1;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * หน้าต่าง Quick Activation (Command)
 * - สร้าง activate.sh + token แล้วแสดงคำสั่งให้ก๊อปไปรันใน adb shell / เทอร์มินัล
 * - ปุ่ม COPY คัดลอกคำสั่ง, CONNECT เชื่อมกับ InputServer, CLOSE ปิดหน้าต่าง
 */
public final class ShellActivationDialog {
    private ShellActivationDialog() {}

    public static void show(Activity activity) {
        show(activity, null);
    }

    public static void show(final Activity activity, final Runnable onConnected) {
        // สร้างไฟล์ activate.sh + token (เขียนทับสคริปต์ทุกครั้ง)
        String cmd;
        try {
            cmd = ShellServerSetup.prepare(activity);
        } catch (Exception e) {
            cmd = ShellServerSetup.getCommand(activity);
        }
        final String command = cmd;

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 40, 48, 24);
        container.setBackgroundColor(Color.parseColor("#1E2230"));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("⌨️ Quick Activation (Command)");
        tvTitle.setTextColor(Color.parseColor("#8BE9FD"));
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 20);
        container.addView(tvTitle);

        TextView tvStep1 = new TextView(activity);
        tvStep1.setText("1. คัดลอกคำสั่งนี้ไปรันในเทอร์มินัลหรือ ADB (adb shell)");
        tvStep1.setTextColor(Color.WHITE);
        tvStep1.setTextSize(13f);
        tvStep1.setPadding(0, 0, 0, 12);
        container.addView(tvStep1);

        // กล่องแสดงคำสั่ง (กดค้างเพื่อเลือกข้อความเองได้)
        TextView tvCmd = new TextView(activity);
        tvCmd.setText(command.isEmpty() ? "(ไม่พบโฟลเดอร์ไฟล์ของแอป)" : command);
        tvCmd.setTextColor(Color.parseColor("#50FA7B"));
        tvCmd.setTextSize(12f);
        tvCmd.setTypeface(Typeface.MONOSPACE);
        tvCmd.setTextIsSelectable(true);
        tvCmd.setPadding(24, 20, 24, 20);
        tvCmd.setBackgroundColor(Color.parseColor("#0F111A"));
        container.addView(tvCmd, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvStep2 = new TextView(activity);
        tvStep2.setText("2. กลับมาที่หน้านี้แล้วกด CONNECT");
        tvStep2.setTextColor(Color.WHITE);
        tvStep2.setTextSize(13f);
        tvStep2.setPadding(0, 16, 0, 0);
        container.addView(tvStep2);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(container)
                .setNeutralButton("COPY", null)
                .setNegativeButton("CLOSE", null)
                .setPositiveButton("CONNECT", null)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#1E2230")));
        }
        dialog.show();

        // ตั้ง listener หลัง show() เพื่อไม่ให้ dialog ปิดเองตอนกด COPY / CONNECT
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            ShellServerSetup.copyCommand(activity);
            Toast.makeText(activity, "คัดลอกคำสั่งแล้ว", Toast.LENGTH_SHORT).show();
        });

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Toast.makeText(activity, "กำลังเชื่อมต่อ...", Toast.LENGTH_SHORT).show();
            ShellServerClient.connectAsync(ok -> activity.runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(activity, "✅ Connected!", Toast.LENGTH_LONG).show();
                    if (onConnected != null) onConnected.run();
                    dialog.dismiss();
                } else {
                    Toast.makeText(activity,
                            "❌ เชื่อมต่อไม่ได้ ตรวจสอบว่ารันคำสั่งในข้อ 1 สำเร็จแล้ว",
                            Toast.LENGTH_LONG).show();
                }
            }));
        });
    }
}
