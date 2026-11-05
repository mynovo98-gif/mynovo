package com.mynovo.mynovo1;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class PairDialog {

    public static void show(final Activity act) {
        int p = (int) (16 * act.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p, p, 0);

        final EditText host = new EditText(act);
        host.setHint("IP เช่น 127.0.0.1");
        host.setText("127.0.0.1");
        box.addView(host);

        final EditText port = new EditText(act);
        port.setHint("พอร์ตจับคู่ (จากหน้าต่างรหัสจับคู่)");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(port);

        final EditText code = new EditText(act);
        code.setHint("รหัส 6 หลัก");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(code);

        new AlertDialog.Builder(act)
            .setTitle("จับคู่ ADB ไร้สาย")
            .setView(box)
            .setNegativeButton("ยกเลิก", null)
            .setPositiveButton("จับคู่", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    final String h = host.getText().toString().trim();
                    final String c = code.getText().toString().trim();
                    final int pt;
                    try {
                        pt = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        toast(act, "พอร์ตไม่ถูกต้อง");
                        return;
                    }
                    if (c.length() != 6) {
                        toast(act, "รหัสต้องมี 6 หลัก");
                        return;
                    }
                    doPair(act, h, pt, c);
                }
            }).show();
    }

    private static void doPair(final Activity act, final String h, final int pt, final String c) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = AdbManager.get(act).pair(h, pt, c);
                    toast(act, ok ? "จับคู่สำเร็จ" : "จับคู่ไม่สำเร็จ");
                } catch (Throwable e) {
                    toast(act, "ผิดพลาด: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}