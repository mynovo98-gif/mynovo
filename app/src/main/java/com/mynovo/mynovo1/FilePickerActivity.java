package com.mynovo.mynovo1;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Activity โปร่งใสสำหรับเลือกไฟล์ (Storage Access Framework)
 * ใช้ส่งออก/นำเข้าไฟล์พรีเซ็ตปุ่ม (.mnv) ให้ FloatingService (Service เปิดตัวเลือกไฟล์เองไม่ได้)
 * ไม่ต้องขอ permission เพิ่ม
 */
public class FilePickerActivity extends Activity {

    public static final String EXTRA_MODE = "mode";   // "export" | "import"
    public static final String EXTRA_NAME = "name";   // ชื่อไฟล์ที่แนะนำตอนส่งออก

    // ข้อความโค้ดที่ FloatingService ฝากมาให้เขียนลงไฟล์
    public static volatile String pendingExport = null;
    public static volatile boolean alive = false;

    private static final int REQ = 7001;
    private static final int MAX_BYTES = 512 * 1024;

    private boolean export;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        FloatingService.pickerOpen = true;
        export = "export".equals(getIntent().getStringExtra(EXTRA_MODE));
        if (b != null) return; // ถูกสร้างใหม่ (เช่นหมุนจอ) รอผลลัพธ์เดิม

        try {
            Intent i;
            if (export) {
                String name = getIntent().getStringExtra(EXTRA_NAME);
                if (name == null || name.isEmpty()) name = "mynovo_preset.mnv";
                i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, name);
            } else {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
            }
            startActivityForResult(i, REQ);
        } catch (Exception e) {
            Toast.makeText(this, "❌ เปิดตัวเลือกไฟล์ไม่ได้", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                if (export) writeFile(u); else readFile(u);
            } catch (Exception e) {
                Toast.makeText(this, "❌ " + (export ? "บันทึก" : "อ่าน") + "ไฟล์ไม่สำเร็จ",
                        Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    private void writeFile(Uri u) throws Exception {
        String text = pendingExport;
        if (text == null) throw new IllegalStateException("no data");
        OutputStream os = getContentResolver().openOutputStream(u, "wt");
        if (os == null) throw new IllegalStateException("no stream");
        try {
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            os.close();
        }
        Toast.makeText(this, "✅ บันทึกไฟล์เรียบร้อย", Toast.LENGTH_SHORT).show();
    }

    private void readFile(Uri u) throws Exception {
        InputStream is = getContentResolver().openInputStream(u);
        if (is == null) throw new IllegalStateException("no stream");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    Toast.makeText(this, "❌ ไฟล์ใหญ่เกินไป", Toast.LENGTH_LONG).show();
                    return;
                }
                bos.write(buf, 0, n);
            }
        } finally {
            is.close();
        }
        FloatingService s = FloatingService.instance;
        if (s != null) s.onFileImported(bos.toString("UTF-8"));
        else Toast.makeText(this, "❌ ระบบปุ่มไม่ได้ทำงานอยู่", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        pendingExport = null;
        FloatingService.pickerOpen = false;
        super.onDestroy();
    }
}
