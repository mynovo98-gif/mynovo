package com.mynovo.mynovo1.security;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * ตัวอย่างการใช้งาน SecurityGuard ใน MainActivity / Application class
 *
 * แนวคิดสำคัญ: กระจายจุดเรียกเช็ค และ "ตอบสนองเงียบ ๆ" แทนการ crash ทันที
 * เพื่อให้คนที่พยายาม patch หาสาเหตุได้ยากขึ้น
 */
public final class SecurityGuardUsage {

    private SecurityGuardUsage() {}

    private static final Random RANDOM = new Random();

    /** เรียกตอน onCreate() ของ Activity หลัก หรือใน Application.onCreate() */
    public static void checkOnStartup(Activity activity) {
        SecurityGuard.SecurityCheckResult result =
                SecurityGuard.runSecurityChecks(activity, false);

        if (result.isTampered) {
            handleTampering(activity);
        }

        // เช็คซ้ำแบบสุ่มระหว่างใช้งาน ไม่ใช่แค่ตอนเปิดแอปครั้งเดียว
        scheduleRandomRecheck(activity);
    }

    private static void scheduleRandomRecheck(final Activity activity) {
        final Handler handler = new Handler(Looper.getMainLooper());
        long delayMs = 30_000 + (long) (RANDOM.nextDouble() * 90_000); // สุ่มช่วง 30s-2min

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing()) {
                    SecurityGuard.SecurityCheckResult result =
                            SecurityGuard.runSecurityChecks(activity);
                    if (result.isTampered) {
                        handleTampering(activity);
                    }
                    scheduleRandomRecheck(activity); // เช็คต่อไปเรื่อย ๆ
                }
            }
        }, delayMs);
    }

    /**
     * ตัวเลือกการตอบสนองเมื่อพบการแก้ไข/เครื่องมือ hack:
     *
     * แนวทางที่ 1 (ตรงไปตรงมา): ปิดแอปทันที
     *   - ข้อดี: ชัดเจน ผู้ใช้ทั่วไปรู้ว่าแอปไม่ยอมรัน
     *   - ข้อเสีย: คนแก้ไขรู้ทันทีว่า check อยู่ตรงไหน หา exit() แล้ว patch ทิ้งง่าย
     *
     * แนวทางที่ 2 (แนะนำ): ทำให้ฟีเจอร์สำคัญ "เพี้ยน" แบบเนียน ๆ
     *   - เช่น ส่งข้อมูลผิดไป server, ปิดฟีเจอร์หลักแบบไม่แจ้งเตือน,
     *     หรือ delay การทำงานแบบสุ่มจนใช้งานไม่ได้จริง
     *   - ข้อดี: คนแก้ไขงงว่าทำไมแอป "รันได้แต่ไม่ทำงานจริง" หา root cause ยากกว่ามาก
     *
     * เลือกใช้ตาม risk ของแอป ถ้าเป็นแอปมีข้อมูล sensitive แนะนำแนวทางที่ 2
     * ร่วมกับการแจ้งเตือนไป server (ไม่ใช่แค่ log ในเครื่อง เพราะ log ก็โดนลบได้)
     */
    private static void handleTampering(Activity activity) {
        // ตัวอย่าง: แนวทางที่ 2 - ปิดการทำงานแบบเงียบ ไม่ throw/log ชัดเจน
        // TODO: แทนที่ด้วย logic จริงของแอป เช่น:
        // - ปิด feature flag สำคัญใน memory (ไม่ throw exception)
        // - ส่ง signal ไป backend ว่า client นี้ไม่ผ่าน integrity check
        //   (แล้วให้ backend ปฏิเสธ request ของ client นี้แทนที่จะพึ่ง client บอกตัวเอง)

        // ตัวอย่างแนวทางที่ 1 ถ้าต้องการความเรียบง่าย:
        // activity.finishAffinity();
        // Runtime.getRuntime().exit(0);
    }
}
