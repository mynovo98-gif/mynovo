package com.mynovo.mynovo1;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

public class AutoPairDialog {

    private static final String HOST = "127.0.0.1";

    public static void show(final Activity act) {
        int p = (int) (16 * act.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p, p, 0);

        final TextView info = new TextView(act);
        info.setText("กำลังหาพอร์ตจับคู่... เปิดหน้า \"จับคู่ด้วยรหัสการจับคู่\" ค้างไว้");
        box.addView(info);

        final EditText code = new EditText(act);
        code.setHint("รหัส 6 หลัก");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(code);

        final int[] pairPort = {0};

        final MdnsDiscovery disc = new MdnsDiscovery(act, MdnsDiscovery.TYPE_PAIRING,
            new MdnsDiscovery.Callback() {
                @Override public void onFound(int port) {
                    pairPort[0] = port;
                    info.setText("พบพอร์ตจับคู่: " + port + " — กรอกรหัสแล้วกดจับคู่");
                }
                @Override public void onLost() {
                    pairPort[0] = 0;
                    info.setText("หน้าต่างรหัสถูกปิด เปิดใหม่อีกครั้ง");
                }
                @Override public void onError(String message) {
                    info.setText("ผิดพลาด: " + message);
                }
            });
        disc.start();

        final AlertDialog dlg = new AlertDialog.Builder(act)
            .setTitle("จับคู่ ADB ไร้สาย")
            .setView(box)
            .setNegativeButton("ยกเลิก", null)
            .setPositiveButton("จับคู่", null)
            .create();

        dlg.setOnDismissListener(d -> disc.stop());
        dlg.show();

        // กันไดอะล็อกปิดเองตอนกรอกไม่ครบ
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            if (pairPort[0] == 0) {
                Toast.makeText(act, "ยังไม่พบพอร์ตจับคู่", Toast.LENGTH_SHORT).show();
                return;
            }
            if (c.length() != 6) {
                Toast.makeText(act, "รหัสต้องมี 6 หลัก", Toast.LENGTH_SHORT).show();
                return;
            }
            final int port = pairPort[0];
            new Thread(() -> {
                try {
                    boolean ok = AdbManager.get(act).pair(HOST, port, c);
                    act.runOnUiThread(() -> {
                        Toast.makeText(act, ok ? "จับคู่สำเร็จ" : "จับคู่ไม่สำเร็จ",
                                Toast.LENGTH_LONG).show();
                        if (ok) dlg.dismiss();
                    });
                } catch (Throwable e) {
                    act.runOnUiThread(() -> Toast.makeText(act,
                            "ผิดพลาด: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        });
    }
}
