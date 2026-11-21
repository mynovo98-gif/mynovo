package com.mynovo.mynovo1;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class FilePickerActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_NAME = "name";   // ชื่อไฟล์ตอนส่งออก

    // ข้อความที่จะเขียนลงไฟล์ (ตั้งโดย FloatingService ก่อนเปิด)
    public static volatile String pendingExport = null;
    // true เมื่อ Activity นี้เปิดอยู่ (FloatingService ใช้เช็กว่าเปิดขึ้นจริงไหม)
    public static volatile boolean alive = false;

    private static final int REQ_EXPORT = 9101;
    private static final int REQ_IMPORT = 9102;
    private static final int MAX_IMPORT_BYTES = 256 * 1024;

    private String mode = "import";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alive = true;
        FloatingService.pickerOpen = true;
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = "import";

        if (savedInstanceState != null) return; // ระบบสร้างใหม่ (หมุนจอ ฯลฯ) ไม่ต้องเปิดซ้ำ

        try {
            Intent i;
            if ("export".equals(mode)) {
                String name = getIntent().getStringExtra(EXTRA_NAME);
                if (name == null || name.isEmpty()) name = "mynovo_preset.mnv";
                i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_EXPORT);
            } else {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            }
        } catch (Exception e) {
            Toast.makeText(this, "❌ เปิดตัวเลือกไฟล์ไม่ได้", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();

            if (requestCode == REQ_EXPORT) {
                String text = pendingExport;
                if (text == null) return;
                OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                if (os == null) throw new Exception("no stream");
                os.write(text.getBytes("UTF-8"));
                os.flush();
                os.close();
                Toast.makeText(this, "✅ ส่งออกไฟล์สำเร็จ", Toast.LENGTH_SHORT).show();

            } else if (requestCode == REQ_IMPORT) {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception("no stream");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMPORT_BYTES) {
                        is.close();
                        Toast.makeText(this, "❌ ไฟล์ใหญ่เกินไป", Toast.LENGTH_LONG).show();
                        return;
                    }
                    bos.write(buf, 0, n);
                }
                is.close();
                String text = bos.toString("UTF-8").trim();

                FloatingService svc = FloatingService.instance;
                if (svc != null) {
                    svc.onFileImported(text);
                } else {
                    Toast.makeText(this, "❌ ระบบไม่ได้ทำงานอยู่", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "❌ อ่าน/เขียนไฟล์ไม่สำเร็จ", Toast.LENGTH_LONG).show();
        } finally {
            pendingExport = null;
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        FloatingService.pickerOpen = false;
        pendingExport = null;
        super.onDestroy();
    }
}