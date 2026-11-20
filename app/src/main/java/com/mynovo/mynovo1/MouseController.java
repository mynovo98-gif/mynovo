package com.mynovo.mynovo1;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class MouseController {
    // ความทึบของหน้าต่างล็อคเมาส์ (รับ input การขยับ/คลิก) - ถ้าเพิ่มแล้ว D-pad/ปุ่มแตะกลับมากดไม่ลง ให้ลดลง
    private static final float MOUSE_LOCK_ALPHA = 0.05f;
    // ความทึบของหน้าต่างเคอร์เซอร์ (ชั้นที่วาดไอคอนเมาส์) - ปรับแยกจากหน้าต่างล็อคเมาส์ได้อิสระ
    private static final float CURSOR_LAYER_ALPHA = 0.40f;

    // true  = นิ้วเสมือนลากเลยขอบจอได้ (พิกัดติดลบ/เกินจอ) ไม่ต้องยกนิ้ว กล้องหมุนลื่น
    // false = จำกัดในจอ แล้วยกนิ้ววางใหม่เมื่อถึงระยะ (กล้องจะกระตุกตอนรีเซ็ต)
    // ถ้าเปิดแล้วกล้องไม่ขยับ/หยุดตอนเลยขอบ ให้ตั้งเป็น false
    // (ใช้กับระบบบิตเท่านั้น ระบบคงที่ใช้โซนความเร็วคงที่ด้านล่างเสมอ)
    private static final boolean VIEW_ALLOW_OFFSCREEN = true;

    // ลอยได้ไกลสุดจากจุดเริ่มกี่เท่าของจอ ก่อนรีเซ็ต (เดิม 50 เท่า) ลดแล้วชดเชยแม่นขึ้น
    // ถ้ากล้องกระตุกบ่อยให้เพิ่ม (เช่น 2.5), ถ้ายังช้าเร็วไม่คงที่ให้ลด (เช่น 1.0)
    private static final float VIEW_LIMIT_SCREENS = 50f;

    // true = โหมดความเร็วคงที่: ความเร็ว = ระยะเมาส์ x ค่าความไว ล้วนๆ ไม่มีตัวคูณตามตำแหน่งเลย (ทั้ง X และ Y)
    //        นิ้วเสมือนอยู่ในโซนฝั่งขวา (ที่เกมให้ความเร็วเท่ากัน) ไม่ออกนอกจอ
    //        ถ้าออกนอกโซนจะวางนิ้วใหม่ที่ปลายด้านตรงข้ามทิศที่หมุน (ได้ระยะลากไกลสุด)
    // false = พฤติกรรมเดิม (ตัวชดเชยตามตำแหน่ง)
    // [แก้ไข] ระบบคงที่ (viewFlickAccel = false) เปิดโซนนี้ให้อัตโนมัติ ผ่านตัวแปร useZone ใน handlePointerEvent
    //         ค่านี้จึงบังคับเปิดโซนให้ "ระบบบิต" ด้วยหรือไม่ (ปกติ false = ระบบบิตทำงานเหมือนเดิม)
    private static final boolean VIEW_CONSTANT_ZONE = false;
    // ขอบเขตโซน (สัดส่วนของจอ) = ระยะที่นิ้วเสมือน "ลาก" ได้ในโหมดคงที่
    // ถ้าฝั่งซ้ายของโซนกล้องหมุนเร็วกว่าฝั่งขวา ให้เพิ่ม ZONE_X_MIN (เช่น 0.5)
    // [หมายเหตุ] ถ้า VIEW_X_FREE_IN_ZONE = true ค่า ZONE_X_* ไม่ถูกใช้แล้ว (ไม่ว่าแกน Y จะตั้งเป็นอะไร)
    private static final float ZONE_X_MIN = 0.56f;
    private static final float ZONE_X_MAX = 0.88f;
    // [หมายเหตุ] ZONE_Y_*, ZONE_RESTART ใช้เฉพาะตอน VIEW_Y_FREE_IN_ZONE = false (พฤติกรรมเดิมของแกน Y)
    private static final float ZONE_Y_MIN = 0.12f;
    private static final float ZONE_Y_MAX = 0.88f;
    // วางนิ้วใหม่ที่ตำแหน่งกี่ % ของโซนจากปลายด้านตรงข้าม (ใช้กับแกน Y เท่านั้น)
    private static final float ZONE_RESTART = 0.04f;

    // [เพิ่ม] สวิตช์แกน Y ของ "ระบบคงที่" (ไม่ได้แก้)
    // true  = แกน Y ลากทะลุขอบจอได้ (พิกัดติดลบ/เกินจอ) ไม่ยก-วางนิ้วใหม่ ไม่รีเซ็ตตอนเมาส์หยุด
    // false = พฤติกรรมเดิม (Y หลุดโซนแล้วยก-วางใหม่ + รีเซ็ต Y ตอนเมาส์หยุด)
    private static final boolean VIEW_Y_FREE_IN_ZONE = true;
    // [เพิ่ม] ลอยไกลสุดของแกน Y กี่เท่าของความสูงจอ (แค่หยุดที่เพดาน ไม่ยกนิ้ว) กันตัวเลขลอยไกลเกินจริง
    private static final float VIEW_Y_LIMIT_SCREENS = 50f;

    // [แก้ไข X] สวิตช์แกน X ของ "ระบบคงที่" (ไม่ต้องพึ่ง VIEW_Y_FREE_IN_ZONE แล้ว)
    // true  = แกน X ลากทะลุขอบจอได้ (พิกัดติดลบ/เกินจอ) โดยกดค้างนิ้วเดียวที่ปุ่มล็อคเมาส์ตลอด
    //         ไม่ชนขอบโซน ไม่ยก-วางนิ้วใหม่ ไม่รีเซ็ตตอนเมาส์หยุด (ไม่ขึ้นกับการตั้งค่าแกน Y)
    // false = พฤติกรรมเดิม (X ชนขอบโซนแล้วยก-วางใหม่)
    private static final boolean VIEW_X_FREE_IN_ZONE = true;
    // [เพิ่มใหม่] ลอยไกลสุดของแกน X กี่เท่าของความกว้างจอ (แค่หยุดที่เพดาน ไม่ยกนิ้ว) กันตัวเลขลอยไกลเกินจริง
    // ถ้ากล้องไม่ขยับตอนนิ้วอยู่นอกจอไกลๆ ให้ลดค่านี้ (เช่น 5-10)
    private static final float VIEW_X_LIMIT_SCREENS = 50f;

    // [เพิ่ม] ช่วงปลอดภัยของแกน X ตอน "วางนิ้วใหม่" (DOWN) ของโหมดคงที่ (สัดส่วนของความกว้างจอ)
    // เหตุผล: เดิมวางนิ้วใหม่ที่ปลายโซนสุดๆ (~37% / ~95%) ซึ่งอาจทับปุ่มจอย/ปุ่มอื่น หรือชิดขอบจอจนระบบมองเป็นปัดจากขอบ
    //         เกมจึงรับเป็นการ "ทัชตรงอื่น" ตอนนี้ DOWN จะอยู่ในช่วงนี้เสมอ ส่วนระยะลาก (MOVE) ยังกว้างตามโซนเดิม
    // หมุนไปขวา -> วางใหม่ที่ DOWN_SAFE_X_MIN / หมุนไปซ้าย -> วางใหม่ที่ DOWN_SAFE_X_MAX
    // ปรับให้ตรงกับพื้นที่ว่าง (ไม่มีปุ่ม) ของเกมคุณ
    private static final float DOWN_SAFE_X_MIN = 0.56f;
    private static final float DOWN_SAFE_X_MAX = 0.94f;
    // [เพิ่ม] สบัดแรงจนข้ามโซนได้กี่รอบในเฟรมเดียว (ระยะที่เกินจะยกไปทำต่อหลังวางนิ้วใหม่ ไม่ทิ้ง)
    private static final int MAX_ZONE_WRAPS = 6;

    // เมาส์หยุดขยับกี่ ms ถึงรีเซ็ตนิ้วกลับจุดเริ่ม (กล้องนิ่งอยู่ จึงไม่เห็นกระตุก)
    private static final long VIEW_IDLE_RECENTER_MS = 300L;

    // [แก้ไข] สวิตช์ "รีเซ็ตแกน X ตอนเมาส์หยุดขยับ" ของ "ระบบคงที่" เท่านั้น
    // อาการที่แก้: ตอนยกเมาส์ขึ้นมาวางใหม่ (เมาส์หยุดส่ง event เกิน 250ms) ระบบเคยยก-วางนิ้วเสมือนใหม่เอง
    //             ทำให้เกมเห็นนิ้วถูกปล่อย/ทัชใหม่ = กล้องกระตุก/รีเซ็ตเอง
    // false = ไม่พา X กลับกลางโซนตอนเมาส์หยุด (X ค้างที่เดิม) <- แนะนำ
    // true  = พาแกน X กลับกลางโซน (แกน Y ไม่ถูกแตะถ้า VIEW_Y_FREE_IN_ZONE = true)
    //         [หมายเหตุ] ถ้า VIEW_X_FREE_IN_ZONE = true ค่านี้ถูกข้าม (X ไม่รีเซ็ตเด็ดขาด)
    // ระบบบิตไม่ถูกแก้ ทำงานเหมือนเดิมทุกอย่าง
    private static final boolean IDLE_RECENTER_CONSTANT_X = false;

    // [เพิ่ม] ช่วงปลอดภัยของ Y ตอน "วางนิ้วใหม่" (สัดส่วนของความสูงจอ)
    // DOWN ใหม่ห้ามอยู่ติดขอบบน/ล่าง ไม่งั้นระบบมองเป็นการปัดจากขอบ (ดึงแถบแจ้งเตือน/ท่าทางโฮม)
    // นิ้วที่ลากเลยขอบหลังวางแล้วยังทะลุได้เหมือนเดิม (ไม่เกี่ยวกับค่านี้)
    private static final float DOWN_SAFE_Y_MIN = 0.26f;
    private static final float DOWN_SAFE_Y_MAX = 0.88f;

    // ระยะลากสูงสุดของโหมดมุมมองจากจุดเริ่ม (สัดส่วนของความกว้าง/สูงจอ) เกินแล้วจะยกนิ้วแล้ววางใหม่
    // เพิ่มค่า = ลากได้ไกลขึ้นก่อนรีเซ็ต, ลดค่า = รีเซ็ตถี่ขึ้น
    private static final float VIEW_RANGE_X = 0.10f;
    private static final float VIEW_RANGE_Y = 0.12f;

    // เกมให้ความเร็วกล้องครึ่งจอซ้ายเร็วกว่าครึ่งขวา เลยชดเชยให้เท่ากัน (ไม่กั้นนิ้ว)
    // LEFT_HALF_GAIN: 1.0 = ปิดการชดเชย (ความเร็วคงที่ทั้งจอ) / ถ้าซ้ายเร็วกว่าขวาให้ลดลง (เช่น 0.5)
    // LEFT_HALF_X = เส้นแบ่งซ้าย/ขวา, LEFT_HALF_BAND = ความกว้างช่วงเปลี่ยนค่อยๆ (กันกระโดดตรงเส้นแบ่ง)
    private static final float LEFT_HALF_X = 0.525f;
    private static final float LEFT_HALF_GAIN = 0.9f;
    private static final float LEFT_HALF_BAND =  1.0f;

    // [แก้ไขแก้หน่วง] ตัวลดความเร็วเพิ่มเติมฝั่งซ้าย (ใช้ร่วมกับ leftHalfFactor)
    // ตั้งเป็น 1.0 = ปิดตัวลดนี้ทั้งหมด (ทดสอบว่าหายหน่วงหรือไม่)
    // ถ้าหายหน่วงแล้วฝั่งซ้ายเร็วกว่าขวา ค่อยๆ ลดทีละนิด เช่น 0.9 -> 0.8 -> 0.7
    private static final float LEFT_EXTRA_GAIN = 1.0f;
    // เส้นแบ่งซ้าย/ขวา (สัดส่วนของความกว้างจอ)
    private static final float LEFT_EXTRA_X = 0.5f;
    // ความกว้างช่วงเปลี่ยนนุ่มๆ รอบเส้นแบ่ง (สัดส่วนของความกว้างจอ)
    private static final float LEFT_EXTRA_BAND = 0.05f;

    // ช่วงความเร็วดิบ (px/ms ก่อนคูณความไว) ที่เริ่มนับว่า "เริ่มสะบัดเร็ว" ถึง "สะบัดเร็วสุด"
    private static final float LEFT_FAST_SPEED_MIN = 0.8f;
    private static final float LEFT_FAST_SPEED_MAX = 0.9f;
    // [แก้ไขแก้หน่วง] gain ของฝั่งซ้ายตอนสะบัดเร็วสุด ตั้งเป็น 1.0 = ปิดการหรี่ตอนสะบัด
    // ถ้าสะบัดเร็วแล้วฝั่งซ้ายพุ่งเกิน ค่อยๆ ลดลง (เช่น 0.9 -> 0.8)
    private static final float LEFT_EXTRA_GAIN_FAST = 0.85f;

    // [เพิ่ม] ตัวคูณความเร็วตอน "หันซ้าย" (ตามทิศที่เมาส์ขยับ ไม่เกี่ยวกับตำแหน่งนิ้วเสมือน)
    // 1.0 = เท่าเดิม, 0.9 = ช้าลง 10%, 0.8 = ช้าลง 20%, 0.7 = ช้าลง 30%
    private static final float TURN_LEFT_GAIN = 0.9f;

    // [แก้ไขแก้หน่วง] สวิตช์ตัวชดเชยตอนนิ้วเสมือนลากหลุดขอบจอ
    // false = ปิดตัวชดเชย ความเร็ว = ระยะเมาส์ x ความไว เป็นเส้นตรงจริงๆ (ไม่มีตัวลดตอนนิ้วนอกจอ)
    // true  = เปิดตัวชดเชย (ถ้านอกจอแล้วเกมเร็วขึ้นจริงๆ ให้เปิดแล้วปรับ OFFSCREEN_GAIN_X/MIN_X)
    private static final boolean OFFSCREEN_COMPENSATE = false;

    // ตอนนิ้วเสมือนลากหลุดขอบจอ เกมให้ความเร็วเร็วขึ้น เลยชดเชยให้เท่าตอนอยู่ในจอ (ใช้ทั้งแกน X และ Y)
    // OFFSCREEN_GAIN: 1.0 = ปิดการชดเชย (ความเร็วคงที่ทั้งแกน X และ Y)
    private static final float OFFSCREEN_GAIN_X = 0.45f;
    // ตัวคูณต่ำสุดของแกน X นอกจอ (กันช้าลงเรื่อยๆ ตอนสะบัดไกล) เพิ่ม = นอกจอเร็วขึ้น
    private static final float OFFSCREEN_MIN_X = 0.40f;
    // แกน Y กลับไปใช้ค่าเดิม (1.0 = ไม่ชดเชย)
    private static final float OFFSCREEN_GAIN_Y = 1.0f;
    // ระยะที่ค่อยๆ เปลี่ยนความเร็วเมื่อเลยขอบออกไป (สัดส่วนของขนาดจอ) จะได้ไม่กระโดดตรงขอบ
    private static final float OFFSCREEN_BAND = 0.8f;
    // BAND ของแกน Y (ค่าเดิม)
    private static final float OFFSCREEN_BAND_Y = 0.04f;
    private static final boolean OFFSCREEN_STACK_LEFT = false;

    // [แก้ไขแก้หน่วง] กันกล้องพุ่งตอน "ยกเมาส์แล้ววางใหม่" (เฉพาะระบบคงที่)
    // ถ้าห่างจาก MOVE ก่อนหน้านานกว่าค่านี้ ถือว่าเพิ่งพ้นช่วงเงียบ
    // เพิ่มจาก 60 เป็น 150 เพื่อไม่ให้การหยุดมือสั้นๆ แล้วสะบัดต่อโดนทิ้ง event
    // ถ้ายังพุ่งตอนยกเมาส์อยู่บ้าง ให้ลดค่านี้ลง (เช่น 100)
    private static final float POST_GAP_MS = 150f;
    // [แก้ไขแก้หน่วง] เพดานความเร็วดิบ (พิกเซลดิบ/ms) ที่ยอมรับได้ "ทันที" หลังช่วงเงียบ
    // เพิ่มจาก 2.5 เป็น 5.0 ให้ก้อนแรกหลังหยุดมือไม่โดนทิ้งง่ายเกินไป
    // ยังพุ่งตอนยกเมาส์อยู่ -> ลดค่านี้ลง (เช่น 3.5)
    private static final float POST_GAP_MAX_V = 5.0f;
    // ล็อก dt ที่ใช้คำนวณเพดานหลังช่วงเงียบ (ms) เพดาน = POST_GAP_MAX_V x ค่านี้ ไม่ว่ายกเมาส์นานแค่ไหน
    private static final float POST_GAP_CAP_DT_MS = 16f;

    // [เพิ่มใหม่] เพดานความเร็วดิบของ "ระบบคงที่" (พิกเซลดิบของเมาส์/ms ก่อนคูณความไว) เฉพาะแกน X
    // สบัดเมาส์แรงแค่ไหน ความเร็วหมุนก็ไม่เกินค่านี้ (ระยะส่วนที่เกินเพดานจะถูกตัดทิ้ง ไม่เก็บไว้ทำต่อ)
    // ความเร็วปกติที่ต่ำกว่าเพดานไม่ถูกแตะเลย / ระบบบิตไม่ถูกแตะ / 0 = ปิดเพดาน
    // ยังรู้สึกเร็วขึ้นตอนสบัด -> ลดค่านี้ (เช่น 0.8 -> 0.6) / หมุนปกติรู้สึกช้าลง -> เพิ่มค่านี้ (เช่น 1.3)
    private static final float CONST_MAX_RAW_SPEED_X = 1.2f;
    // เพดานแบบเดียวกันของแกน Y (0 = ปิด ไม่แตะแกน Y ตามที่ขอ)
    private static final float CONST_MAX_RAW_SPEED_Y = 0f;
    // ช่วง dt (ms) ที่ใช้คำนวณเพดานต่อ event: ต่ำสุด/สูงสุด (กัน event ที่มาติดกันหรือมาช้าทำให้เพดานเพี้ยน)
    private static final float CONST_CAP_DT_MIN_MS = 2f;
    private static final float CONST_CAP_DT_MAX_MS = 16f;

    // [แก้ไขแก้หน่วง] ความถี่ส่งตำแหน่งนิ้วเสมือนเริ่มต้น (Hz) ก่อนที่หน้าตั้งค่าจะเรียก setViewHz()
    // 0 = ซิงค์เฟรมจอ (อาจดีเลย์ได้ ~16ms บนจอ 60Hz) / 250 = ส่งทันทีที่พ้น interval ตอบสนองไวกว่า
    private static final int DEFAULT_VIEW_HZ = 500;

    // ตัวคูณความเร็วตามระยะที่นิ้วเสมือนเลยขอบจอ แกน X (ลดต่อเนื่อง ไม่มีเพดาน)
    // (v = พิกัดบนแกนนั้น, max = ขนาดจอแกนนั้น)
    private float offscreenFactor(float v, float max, float gainOut) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f) return 1f;
        float t = d / Math.max(1f, OFFSCREEN_BAND * max);
        float g = Math.max(0.05f, gainOut);
        float f = 1f / (1f + (1f / g - 1f) * t);
        return Math.max(OFFSCREEN_MIN_X, f);
    }

    // ตัวคูณแกน Y: สูตรเดิม ลดถึง gainOut แล้วหยุด
    private float offscreenFactorY(float v, float max, float gainOut) {
        float d = v < 0f ? -v : (v > max ? v - max : 0f);
        if (d <= 0f) return 1f;
        float t = Math.min(1f, d / Math.max(1f, OFFSCREEN_BAND_Y * max));
        return 1f - (1f - gainOut) * t;
    }

    // ตัวคูณความเร็วแกน X ตามตำแหน่งนิ้วเสมือน เปลี่ยนค่อยๆ รอบเส้นแบ่ง ไม่กระโดด
    private float leftHalfFactor(float x) {
        float band = Math.max(1f, LEFT_HALF_BAND * maxScreenWidth);
        float t = (x - (LEFT_HALF_X * maxScreenWidth - band)) / (2f * band);
        t = Math.max(0f, Math.min(1f, t));
        return LEFT_HALF_GAIN + (1f - LEFT_HALF_GAIN) * t;
    }

    // [เฉพาะแกน X] ตัวลดความเร็วเพิ่มเติมฝั่งซ้าย: ซ้ายของเส้นแบ่ง = gain (ขึ้นกับความเร็วสะบัด), ขวา = 1.0
    // เปลี่ยนนุ่มๆ (smoothstep) ในช่วง LEFT_EXTRA_BAND รอบเส้นแบ่ง ไม่กระโดด
    private float leftExtraFactor(float x, float rawSpeedPxMs) {
        float mid = LEFT_EXTRA_X * maxScreenWidth;
        float band = Math.max(1f, LEFT_EXTRA_BAND * maxScreenWidth);
        float t = (x - (mid - band)) / (2f * band);
        t = Math.max(0f, Math.min(1f, t));
        t = t * t * (3f - 2f * t);

        float speedT = (rawSpeedPxMs - LEFT_FAST_SPEED_MIN)
                / Math.max(0.001f, LEFT_FAST_SPEED_MAX - LEFT_FAST_SPEED_MIN);
        speedT = Math.max(0f, Math.min(1f, speedT));
        float gain = LEFT_EXTRA_GAIN - (LEFT_EXTRA_GAIN - LEFT_EXTRA_GAIN_FAST) * speedT;

        return gain + (1f - gain) * t;
    }

    // ตัวคูณแกน X: ใช้เฉพาะตัวชดเชยตอนหลุดขอบจอ
    // ถ้า OFFSCREEN_COMPENSATE = false จะคืน 1 เสมอ (ความเร็วเป็นเส้นตรงตามค่าความไว)
    private float factorX(float x) {
        if (!OFFSCREEN_COMPENSATE) return 1f;
        return offscreenFactor(x, maxScreenWidth, OFFSCREEN_GAIN_X);
    }

    private final Context context;
    private final WindowManager windowManager;

    private FrameLayout mouseLockView;
    private WindowManager.LayoutParams mouseLockParams;
    private ImageView customCursorView;

    // หน้าต่างแยกสำหรับเคอร์เซอร์ จะได้ยกขึ้นมาอยู่บนสุดได้ (ไม่ไปแอบหลังเมนู/หน้าตั้งค่า)
    private FrameLayout cursorLayer;
    private WindowManager.LayoutParams cursorLayerParams;

    private boolean isMouseMode = false;
    private boolean isPaused = false;
    private boolean destroyed = false;

    // โหมดพิมพ์: overlay ไม่รับโฟกัส/ไม่จับ pointer capture เพื่อให้เกมได้รับแป้นตรงๆ
    private boolean typingMode = false;

    private float cursorX = 0f;
    private float cursorY = 0f;
    private float maxScreenWidth;
    private float maxScreenHeight;

    private float mouseSensitivity;
    private int cursorSize;

    private long mouseMoveToken = 0;
    private boolean pressing = false;
    // คลิกขวากำลังถูกกดอยู่ (กัน onRightClick ถูกเรียกซ้ำจาก DOWN + BUTTON_PRESS ของการคลิกครั้งเดียว)
    private boolean rightDown = false;
    // สถานะปุ่มขวาของ event ก่อนหน้า ใช้จับการ "กดขวา" จากการเปลี่ยนของ buttonState
    private boolean prevSecondary = false;
    // สถานะปุ่มซ้ายของ event ก่อนหน้า (ไว้จับซ้ายที่กดตอนขวาค้างอยู่ กรณีระบบไม่ส่ง BUTTON_PRESS)
    private boolean prevPrimary = false;

    // นับจำนวนครั้งติดกันที่ TouchSimulator.isPointerDown(2) รายงานว่า "ไม่ได้กดอยู่" ทั้งที่ token ยังไม่เป็น 0
    // กันการ flicker 1 เฟรม ทำให้โดนยก-วางนิ้วใหม่ (รีเซ็ท/กระตุก) ทั้งที่จริงๆ นิ้วยังกดอยู่
    private int lostPointerStreak = 0;

    // เก็บเป็น double กันค่าสะสมถูกปัดทิ้งเมื่อนิ้วเสมือนลอยไกลมาก
    private double viewX = 0.0;
    private double viewY = 0.0;

    // มี inject ที่นัดส่งไว้ (trailing) รออยู่หรือไม่
    private boolean injectScheduled = false;

    // ความถี่ส่งตำแหน่งนิ้วเสมือนของโหมดมุมมอง (Hz) ตั้งจากหน้าตั้งค่าปุ่มล็อกเมาส์
    // 0 = ซิงค์กับเฟรมจอ (Choreographer)
    // มากกว่า 0 = จำกัดความถี่ตามที่ตั้ง (ช่วง 30 - 1000) คำนวณเป็น nanosecond
    private int viewInjectHz = DEFAULT_VIEW_HZ;
    private long lastInjectNs = 0;
    private final android.os.Handler injectHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable injectRunnable = this::doViewInject;

    // ส่งต่อเฟรมจอ (ใช้เมื่อ viewInjectHz = 0)
    private boolean frameQueued = false;
    private final Choreographer.FrameCallback frameCb = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameQueued = false;
            doViewInject();
        }
    };

    // ยกเลิกงานส่งตำแหน่งที่นัดไว้ทั้งหมด (ทั้งแบบ Hz และแบบซิงค์เฟรม)
    private void cancelViewInject() {
        injectHandler.removeCallbacks(injectRunnable);
        injectScheduled = false;
        if (frameQueued) {
            frameQueued = false;
            try {
                Choreographer.getInstance().removeFrameCallback(frameCb);
            } catch (Exception ignored) {}
        }
    }

    // ยก-วางนิ้วเสมือนใหม่ที่พิกัด (nx, ny) UP ที่ตำแหน่งเดิม (บีบให้อยู่ในจอ) แล้ว DOWN ที่ตำแหน่งใหม่
    private void recenterTo(float nx, float ny) {
        recenterTo(nx, ny, false);
    }

    // rawUp = true: UP ที่พิกัดจริงของนิ้ว (ไม่บีบเข้าจอ) ใช้กับระบบคงที่ตอนแกน Y ลอยนอกจอ
    private void recenterTo(float nx, float ny, boolean rawUp) {
        if (mouseMoveToken != 0) {
            float ux, uy;
            if (rawUp) {
                ux = (float) viewX;
                uy = (float) viewY;
            } else {
                ux = (float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX));
                uy = (float) Math.max(0.0, Math.min(maxScreenHeight - 1, viewY));
            }
            TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round(ux), Math.round(uy));
        }
        final float safeTop = maxScreenHeight * DOWN_SAFE_Y_MIN;
        final float safeBottom = maxScreenHeight * DOWN_SAFE_Y_MAX;
        ny = Math.max(safeTop, Math.min(safeBottom, ny));
        viewX = nx;
        viewY = ny;
        mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
    }

    // ส่งตำแหน่งสุดท้ายของนิ้วเสมือน (บีบให้อยู่ในจอ) ก่อนยกนิ้ว กันระยะช่วงท้ายหาย
    private void sendViewMoveClamped() {
        if (mouseMoveToken != 0) {
            TouchSimulator.simulateMoveF(mouseMoveToken, 2,
                    (float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX)),
                    (float) Math.max(0.0, Math.min(maxScreenHeight - 1, viewY)));
        }
    }

    // เหมือนข้างบน แต่ไม่บีบแกน Y (ปล่อย Y ตามจริง รวมตอนอยู่นอกจอ)
    private void sendViewMoveRawY() {
        if (mouseMoveToken != 0) {
            TouchSimulator.simulateMoveF(mouseMoveToken, 2,
                    (float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX)),
                    (float) viewY);
        }
    }

    // เวลา MOVE ล่าสุดของโหมดมุมมอง + ธงว่ามี idle timer นัดไว้แล้วหรือยัง
    private long lastViewMoveUptime = 0;
    private boolean idlePending = false;

    // [แก้ไข] ปิดระบบ "รีเซ็ทตอนเมาส์หยุด" ทั้งหมด (ทั้งระบบบิตและระบบคงที่)
    // นิ้วเสมือนจะค้างอยู่ตำแหน่งสุดท้ายตลอดไป จนกว่าจะมี MOVE ใหม่เข้ามาจริงๆ เท่านั้น
    private void armIdleRecenter() {
        // ตั้งใจเว้นว่างไว้ - ไม่ตั้งตัวจับเวลาใดๆ อีกต่อไป
    }

    // เมาส์หยุดขยับ -> พานิ้วเสมือนกลับจุดเริ่ม (ปัจจุบันไม่ถูกเรียกแล้ว เก็บไว้เฉยๆ)
    private final Runnable idleRecenterRunnable = new Runnable() {
        @Override
        public void run() {
            idlePending = false;
            if (destroyed || isPaused || !isMouseMode || mouseMoveToken == 0) return;
            long since = android.os.SystemClock.uptimeMillis() - lastViewMoveUptime;
            if (since < VIEW_IDLE_RECENTER_MS) {
                idlePending = true;
                injectHandler.postDelayed(this, VIEW_IDLE_RECENTER_MS - since);
                return;
            }

            // ระบบคงที่ + ลากทะลุจอทั้งสองแกน: ส่ง keep-alive (MOVE ตำแหน่งเดิมเป๊ะ) เป็นระยะ
            if (!viewFlickAccel && VIEW_X_FREE_IN_ZONE && VIEW_Y_FREE_IN_ZONE) {
                TouchSimulator.simulateMoveF(mouseMoveToken, 2, (float) viewX, (float) viewY);
                idlePending = true;
                injectHandler.postDelayed(this, VIEW_IDLE_RECENTER_MS);
                return;
            }

            // ห้ามรีเซ็ตตอนกดยิง/กดคลิกค้างอยู่
            if (pressing) return;
            float sx = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;

            // ระบบคงที่
            if (!viewFlickAccel) {
                final boolean idleRecenterX = IDLE_RECENTER_CONSTANT_X && !VIEW_X_FREE_IN_ZONE;

                float zsx = Math.max(maxScreenWidth * ZONE_X_MIN,
                        Math.min(maxScreenWidth * ZONE_X_MAX, sx));

                if (VIEW_Y_FREE_IN_ZONE) {
                    if (idleRecenterX
                            && Math.abs(viewX - zsx) > maxScreenWidth * 0.02f) {
                        recenterTo(zsx, (float) viewY, true);
                    }
                    return;
                }

                // ===== พฤติกรรมเดิม (VIEW_Y_FREE_IN_ZONE = false) =====
                float zsy = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;
                zsy = Math.max(maxScreenHeight * ZONE_Y_MIN,
                        Math.min(maxScreenHeight * ZONE_Y_MAX, zsy));
                if (idleRecenterX) {
                    if (Math.abs(viewX - zsx) > maxScreenWidth * 0.02f
                            || Math.abs(viewY - zsy) > maxScreenHeight * 0.02f) {
                        recenterTo(zsx, zsy);
                    }
                } else {
                    if (Math.abs(viewY - zsy) > maxScreenHeight * 0.02f) {
                        final float dx0 = VIEW_X_FREE_IN_ZONE
                                ? (float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX))
                                : (float) viewX;
                        recenterTo(dx0, zsy);
                    }
                }
                return;
            }

            if (Math.abs(viewX - sx) > maxScreenWidth * 0.02f) {
                recenterTo(sx, (float) viewY);
            }
        }
    };

    // ขอ pointer capture ให้ติดเร็วตอนสลับโหมด: ขอทันที ถ้ายังไม่ติดลองซ้ำทุก 16ms (สูงสุด 20 ครั้ง)
    private int captureTries = 0;
    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || isPaused || typingMode || mouseLockView == null) return;
            boolean ok;
            try {
                mouseLockView.requestFocus();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!mouseLockView.hasPointerCapture()) mouseLockView.requestPointerCapture();
                    ok = mouseLockView.hasPointerCapture();
                } else {
                    ok = mouseLockView.isFocused();
                }
            } catch (Exception e) {
                return;
            }
            if (!ok && ++captureTries < 20) injectHandler.postDelayed(this, 16);
        }
    };

    private void requestCaptureFast() {
        injectHandler.removeCallbacks(captureRunnable);
        captureTries = 0;
        captureRunnable.run();
    }

    // นับจำนวนรอบที่โฟกัสหลุด ถ้าหลุดต่อเนื่องจะบังคับให้ window ขอโฟกัสใหม่
    private int focusMissCount = 0;
    private int forceAttempts = 0;
    private long lastForceTime = 0;

    private float targetBtnX = 0f;
    private float targetBtnY = 0f;
    private boolean hasCustomTarget = false;

    // ความเร็วแยกแกนของโหมดมุมมอง (ตั้งจากหน้าตั้งค่าปุ่มล็อกเมาส์)
    private float viewSensX = 1f;
    private float viewSensY = 1f;

    // ===== ระบบสบัดเมาส์: คงที่ / บิต =====
    // true = ระบบบิต (สบัดแรง = เร็วขึ้น เฉพาะแกน X) / false = ระบบคงที่ (เมาส์เชิงเส้นปกติ + โซนความเร็วคงที่ ทั้ง X และ Y)
    private volatile boolean viewFlickAccel = true;

    // [เลิกใช้] ระบบคงที่ไม่มีเพดานล็อคแล้ว เก็บ setter ไว้กันพรีเซ็ตเก่าพัง
    private volatile float lockMaxV = 0.8f;

    // ระบบบิต: เริ่มเร่งเมื่อเร็วเกินค่านี้ (px/ms)
    private volatile float bitRef = 0.8f;
    // ระบบบิต: แรงเร่ง 0 = ไม่เร่งเพิ่ม, 1 = เร่งตามแรงสบัด, 3 = เร่งแรงมาก
    private volatile float bitGain = 0f;
    // ระบบบิต: ตัวคูณสูงสุด (กันพุ่งไม่จำกัด)
    private volatile float bitMax = 4.0f;

    private long lastMoveNs = 0;

    // ปรับความเร็วสะบัดดิบให้เรียบด้วย EMA ก่อนส่งเข้า leftExtraFactor กันอาการสวิง
    private float smoothedLeftSpeedPxMs = 0f;
    // น้ำหนักของค่าใหม่ในแต่ละเฟรม (0-1): ยิ่งน้อยยิ่งเรียบ/ตอบสนองช้าลง
    private static final float LEFT_SPEED_SMOOTHING_ALPHA = 0.5f;

    public void setViewFlickAccel(boolean on) { viewFlickAccel = on; }
    public void toggleViewFlickAccel() { viewFlickAccel = !viewFlickAccel; }
    public boolean isViewFlickAccel() { return viewFlickAccel; }
    public void setLockMaxV(float v) { lockMaxV = Math.max(0.05f, v); }
    public void setBitParams(float ref, float gain, float max) {
        bitRef = Math.max(0.05f, ref);
        bitGain = Math.max(0f, gain);
        bitMax = Math.max(1f, max);
    }

    private final MouseEventListener eventListener;

    public interface MouseEventListener {
        boolean onMouseClickUI(float x, float y, int action);
        void onRightClick();
        boolean onKeyEvent(KeyEvent event);
        // แจ้งเมื่อ window เสียโฟกัส เพื่อปล่อย D-Pad ไม่ให้ค้าง (KEY_UP จะไม่มาถึงอีก)
        void onWindowFocusLost();
        // กดคลิกซ้ายลง ตอนล็อคเมาส์ (โหมดมุมมอง) -> ค้างนิ้วไว้จนกว่าจะปล่อย
        void onMouseLockedLeftDown();
        // ปล่อยคลิกซ้าย ตอนล็อคเมาส์
        void onMouseLockedLeftUp();
    }

    public MouseController(Context context, WindowManager windowManager, float maxWidth, float maxHeight, int cursorSize, float sensitivity, MouseEventListener listener) {
        this.context = context;
        this.windowManager = windowManager;
        this.maxScreenWidth = maxWidth;
        this.maxScreenHeight = maxHeight;
        this.cursorSize = cursorSize;
        this.mouseSensitivity = sensitivity;
        this.eventListener = listener;

        this.targetBtnX = maxWidth * 0.79f;
        this.targetBtnY = maxHeight * 0.55f;
        this.hasCustomTarget = true;

        initView();
    }

    public void setTargetButtonPosition(float x, float y) {
        this.targetBtnX = x;
        this.targetBtnY = y;
        this.hasCustomTarget = true;
    }

    // ความชันของค่าความไว (ตั้งจากหน้าตั้งค่า): 1.0 = เส้นตรง
    private static final float VIEW_SENS_CURVE = 1.0f;
    // ค่าความไวที่ตอนนี้รู้สึกพอดี แยกแกน (ตรงค่านี้ความเร็วเท่าเดิมเป๊ะ)
    private static final float VIEW_SENS_PIVOT_X = 0.04f;
    private static final float VIEW_SENS_PIVOT_Y = 0.04f;

    // ตัวคูณความเร็วโหมดมุมมอง แยกแกน X / Y
    // [แก้ไขแก้หน่วง] X เพิ่มจาก 0.30 เป็น 0.45 (ความไวต่ำเกินไปทำให้รู้สึกอืด)
    // ถ้าเร็วไปให้ลด (เช่น 0.38) / ถ้ายังช้าให้เพิ่ม (เช่น 0.5)
    private static final float VIEW_SPEED_SCALE_X = 0.45f;
    private static final float VIEW_SPEED_SCALE_Y = 0.35f;

    private float softenViewSens(float s, float pivot, float scale) {
        if (s <= 0f) return 0f;
        return (float) (pivot * Math.pow(s / pivot, VIEW_SENS_CURVE)) * scale;
    }

    public void setViewSensitivity(float sx, float sy) {
        this.viewSensX = softenViewSens(sx, VIEW_SENS_PIVOT_X, VIEW_SPEED_SCALE_X);
        this.viewSensY = softenViewSens(sy, VIEW_SENS_PIVOT_Y, VIEW_SPEED_SCALE_Y);
    }

    // ตั้งความถี่ส่งตำแหน่งของโหมดมุมมอง (Hz) 0 = ซิงค์กับเฟรมจอ
    public void setViewHz(int hz) {
        if (hz <= 0) {
            this.viewInjectHz = 0;
        } else {
            this.viewInjectHz = Math.max(30, Math.min(1000, hz));
        }
    }

    private void initView() {
        mouseLockView = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (eventListener != null && eventListener.onKeyEvent(event)) {
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            // ทวงคืน focus + pointer capture ทันทีที่ window ได้โฟกัสกลับมา
            @Override
            public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (destroyed || isPaused) return;
                if (hasWindowFocus) {
                    if (typingMode) return;
                    forceAttempts = 0;
                    requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requestPointerCapture();
                    }
                } else if (eventListener != null) {
                    eventListener.onWindowFocusLost();
                }
            }
        };
        mouseLockView.setBackgroundColor(Color.parseColor("#01000000"));

        cursorX = maxScreenWidth / 2f;
        cursorY = maxScreenHeight / 2f;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mouseLockView.setFocusable(true);
            mouseLockView.setFocusableInTouchMode(true);

            mouseLockView.setOnCapturedPointerListener((v, e) -> {
                if (!isPaused && !destroyed) handlePointerEvent(e);
                return true;
            });
        }

        mouseLockParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                // ไม่ให้ IME (แป้นพิมพ์ภาษาอื่น) มาดัก/แปลงคีย์ก่อนถึง dispatchKeyEvent
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
        );
        mouseLockParams.gravity = Gravity.TOP | Gravity.START;
        mouseLockParams.x = 0;
        mouseLockParams.y = 0;
        applyNoInsets(mouseLockParams);
        // Android 12+ (API 31) บล็อกทัชที่ทะลุ overlay เมื่อ "ความทึบรวมกัน" เกิน 0.8
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mouseLockParams.alpha = MOUSE_LOCK_ALPHA;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mouseLockParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        windowManager.addView(mouseLockView, mouseLockParams);

        cursorLayer = new FrameLayout(context);
        // ขนาดหน้าต่างเคอร์เซอร์ = ขนาดจอจริงเป๊ะๆ (ไม่ใช้ MATCH_PARENT)
        cursorLayerParams = new WindowManager.LayoutParams(
                Math.round(maxScreenWidth), Math.round(maxScreenHeight),
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        cursorLayerParams.gravity = Gravity.TOP | Gravity.START;
        cursorLayerParams.x = 0;
        cursorLayerParams.y = 0;
        applyNoInsets(cursorLayerParams);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cursorLayerParams.alpha = CURSOR_LAYER_ALPHA;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cursorLayerParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        windowManager.addView(cursorLayer, cursorLayerParams);

        mouseLockView.post(() -> {
            if (!isPaused && !destroyed) setMouseMode(false);
        });
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    // ไม่ให้ระบบหักพื้นที่แถบสถานะ/แถบปุ่มโฮม-ย้อนกลับออกจากหน้าต่าง (Android 11+)
    private void applyNoInsets(WindowManager.LayoutParams p) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                p.setFitInsetsTypes(0);
                p.setFitInsetsSides(0);
            } catch (Throwable ignored) {}
        }
    }

    // ปรับขนาดหน้าต่างเคอร์เซอร์ให้ตรงกับจอจริงทุกครั้งที่ขนาดจอเปลี่ยน (หมุนจอ/ย้ายจอ DeX)
    private void updateOverlaySizes() {
        try {
            if (windowManager == null) return;
            if (cursorLayer != null && cursorLayerParams != null && cursorLayer.getParent() != null) {
                cursorLayerParams.width = Math.round(maxScreenWidth);
                cursorLayerParams.height = Math.round(maxScreenHeight);
                windowManager.updateViewLayout(cursorLayer, cursorLayerParams);
            }
        } catch (Exception ignored) {}
    }

    // ขนาดจอจริงเปลี่ยนได้ (หมุนจอ / เกมเป็นแนวนอนแต่ service ถูกสร้างตอนแอปอยู่แนวตั้ง)
    @SuppressWarnings("deprecation")
    public void refreshScreenSize() {
        try {
            if (windowManager == null) return;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            float w = dm.widthPixels;
            float h = dm.heightPixels;
            if (w <= 0 || h <= 0) return;

            // ยอมรับความต่างเล็กน้อย (<2px ต่อแกน) ว่าเป็น noise ไม่ใช่การเปลี่ยนขนาดจอจริง
            if (Math.abs(w - maxScreenWidth) < 2f && Math.abs(h - maxScreenHeight) < 2f) return;

            float sx = (maxScreenWidth > 0) ? w / maxScreenWidth : 1f;
            float sy = (maxScreenHeight > 0) ? h / maxScreenHeight : 1f;
            maxScreenWidth = w;
            maxScreenHeight = h;
            updateOverlaySizes();

            targetBtnX *= sx; targetBtnY *= sy;
            cursorX = Math.max(0f, Math.min(w - 1, cursorX * sx));
            cursorY = Math.max(0f, Math.min(h - 1, cursorY * sy));
            viewX *= sx; viewY *= sy;

            // ปล่อยนิ้วเมาส์ที่ค้างอยู่ตำแหน่งเก่า ครั้งหน้าที่เมาส์ขยับจะ DOWN ใหม่ในพิกัดที่ถูกต้อง
            if (mouseMoveToken != 0) {
                TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) (viewX / sx)), Math.round((float) (viewY / sy)));
                mouseMoveToken = 0;
            }
        } catch (Exception ignored) {}
    }

    public void pause() {
        if (isPaused) return;
        isPaused = true;
        cancelViewInject();
        injectHandler.removeCallbacks(idleRecenterRunnable);
        idlePending = false;
        injectHandler.removeCallbacks(captureRunnable);
        releaseMouseState();
        if (mouseLockView != null) {
            mouseLockView.setVisibility(View.GONE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.GONE);
            mouseLockView.post(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mouseLockView.releasePointerCapture();
                }
                mouseLockView.clearFocus();
            });
        }
    }

    public void resume() {
        if (destroyed) return;
        if (!isPaused) {
            checkAndRegainFocus();
            return;
        }
        isPaused = false;
        focusMissCount = 0;
        refreshScreenSize();
        if (mouseLockView != null) {
            mouseLockView.setVisibility(View.VISIBLE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
            mouseLockView.post(() -> {
                if (typingMode) return;
                mouseLockView.requestFocus();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mouseLockView.requestPointerCapture();
                }
            });
        }
    }

    public void checkAndRegainFocus() {
        if (destroyed || typingMode || mouseLockView == null || isPaused
                || mouseLockView.getVisibility() != View.VISIBLE) return;

        refreshScreenSize();

        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ok = mouseLockView.hasPointerCapture();
        } else {
            ok = mouseLockView.isFocused();
        }

        if (ok) {
            focusMissCount = 0;
            forceAttempts = 0;
            return;
        }

        // window ได้โฟกัสอยู่แล้วแต่ capture ยังไม่ติด: ขอ capture เฉยๆ ไม่แตะ flag
        if (mouseLockView.hasWindowFocus()) {
            focusMissCount = 0;
            mouseLockView.post(() -> {
                try {
                    mouseLockView.requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mouseLockView.requestPointerCapture();
                    }
                } catch (Exception ignored) {}
            });
            return;
        }

        focusMissCount++;
        long nowMs = android.os.SystemClock.uptimeMillis();
        // บังคับโฟกัสใหม่: ต้องหลุดต่อเนื่อง 2 รอบ ห่างกันอย่างน้อย 5 วิ และไม่เกิน 3 ครั้งจนกว่าจะได้โฟกัสจริง
        if (focusMissCount >= 2 && forceAttempts < 3 && nowMs - lastForceTime > 5000) {
            focusMissCount = 0;
            forceAttempts++;
            lastForceTime = nowMs;
            forceWindowRefocus();
        } else {
            mouseLockView.post(() -> {
                try {
                    mouseLockView.requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mouseLockView.requestPointerCapture();
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void forceWindowRefocus() {
        try {
            if (mouseLockParams == null || windowManager == null || mouseLockView == null) return;
            mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(mouseLockView, mouseLockParams);

            mouseLockView.post(() -> {
                try {
                    if (destroyed || isPaused || typingMode) return;
                    mouseLockParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(mouseLockView, mouseLockParams);
                    mouseLockView.requestFocus();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mouseLockView.requestPointerCapture();
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    // ส่งตำแหน่งนิ้วเสมือนของโหมดมุมมอง
    // Hz = 0: ส่ง 1 ครั้งต่อเฟรมจอ (Choreographer)
    // Hz > 0: ส่งทันทีถ้าพ้น interval แล้ว (leading) ถ้ายังไม่พ้นให้นัดส่งตำแหน่งล่าสุดตอนครบ interval (trailing)
    private void scheduleViewInject() {
        if (viewInjectHz <= 0) {
            if (!frameQueued) {
                try {
                    frameQueued = true;
                    Choreographer.getInstance().postFrameCallback(frameCb);
                } catch (Exception e) {
                    // ไม่มี Looper/Choreographer ให้ใช้ -> ส่งทันที
                    frameQueued = false;
                    doViewInject();
                }
            }
            return;
        }

        long intervalNs = 1_000_000_000L / viewInjectHz;
        long waitNs = lastInjectNs + intervalNs - System.nanoTime();

        if (waitNs <= 0) {
            // พ้นช่วงแล้ว ส่งเลย (leading edge)
            if (injectScheduled) {
                injectHandler.removeCallbacks(injectRunnable);
                injectScheduled = false;
            }
            doViewInject();
        } else if (!injectScheduled) {
            // ยังไม่ถึงเวลา นัดส่งตำแหน่งล่าสุดตอนครบ interval (trailing)
            injectScheduled = true;
            injectHandler.postDelayed(injectRunnable, Math.max(1L, waitNs / 1_000_000L));
        }
    }

    private void doViewInject() {
        injectScheduled = false;
        if (destroyed || isPaused || !isMouseMode || mouseMoveToken == 0) return;
        lastInjectNs = System.nanoTime();
        // ส่งพิกัดทศนิยม ไม่ปัดเป็นพิกเซลเต็ม ความเร็วจะไม่เป็นขั้นบันได
        TouchSimulator.simulateMoveF(mouseMoveToken, 2, (float) viewX, (float) viewY);
    }

    private void handlePointerEvent(MotionEvent e) {
        // ตรวจปุ่มขวาจากการเปลี่ยนของ buttonState ในทุก event
        boolean secNow = (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
        if (secNow && !prevSecondary && !rightDown) {
            rightDown = true;
            eventListener.onRightClick();
        }
        if (!secNow) rightDown = false;
        prevSecondary = secNow;

        // จับปุ่มซ้ายจากการเปลี่ยนของ buttonState (กดซ้ายตอนขวายังค้างอยู่)
        boolean priNow = (e.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0;
        int act0 = e.getActionMasked();
        if (isMouseMode && act0 != MotionEvent.ACTION_DOWN && act0 != MotionEvent.ACTION_BUTTON_PRESS
                && act0 != MotionEvent.ACTION_UP && act0 != MotionEvent.ACTION_BUTTON_RELEASE) {
            if (priNow && !prevPrimary && !pressing) {
                pressing = true;
                eventListener.onMouseLockedLeftDown();
            } else if (!priNow && prevPrimary && pressing) {
                pressing = false;
                eventListener.onMouseLockedLeftUp();
            }
        }
        prevPrimary = priNow;

        // ปล่อยสถานะคลิกขวาเมื่อปุ่มขวาไม่ได้ถูกกดแล้ว
        int act = e.getActionMasked();
        if (rightDown && act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_BUTTON_PRESS
                && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) == 0) {
            rightDown = false;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float sumDx = e.getX();
                float sumDy = e.getY();
                for (int h = 0; h < e.getHistorySize(); h++) {
                    sumDx += e.getHistoricalX(h);
                    sumDy += e.getHistoricalY(h);
                }

                float speed = (float) Math.hypot(sumDx, sumDy);
                // โหมดมุมมอง (ล็อคเมาส์): ใช้ความเร็วคงที่ ไม่มีความเร่ง
                // โหมดเคอร์เซอร์ปกติ: ยังมีความเร่งเหมือนเดิม
                float gain = isMouseMode
                        ? mouseSensitivity
                        : mouseSensitivity * (0.5f + Math.min(speed, 1f) * 0.0625f);

                if (isMouseMode) {
                    // ถ้านิ้วเมาส์ (ID 2) ถูกล้างไปแล้ว ให้ DOWN ใหม่อัตโนมัติ (debounce 2 ครั้งกัน flicker)
                    boolean tokenGone = mouseMoveToken == 0;
                    boolean reportedUp = !tokenGone && !TouchSimulator.isPointerDown(2);
                    if (reportedUp) {
                        lostPointerStreak++;
                    } else {
                        lostPointerStreak = 0;
                    }
                    if (tokenGone || lostPointerStreak >= 2) {
                        lostPointerStreak = 0;
                        if (!viewFlickAccel && VIEW_X_FREE_IN_ZONE) {
                            // ระบบคงที่: วางนิ้วกลับที่ตำแหน่ง X ปัจจุบัน (บีบเข้าจอเฉพาะตอน DOWN)
                            viewX = Math.max(0.0, Math.min(maxScreenWidth - 1, viewX));
                            viewY = VIEW_Y_FREE_IN_ZONE
                                    ? Math.max(0.0, Math.min(maxScreenHeight - 1, viewY))
                                    : (hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f);
                        } else {
                            viewX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                            viewY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;
                        }
                        mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
                    }

                    // คำนวณ dt เสมอ เพื่อใช้ตรวจจับ "ช่วงเงียบ" (มือยกเมาส์ขึ้น) ของระบบคงที่ด้วย
                    long nowNs = System.nanoTime();
                    float rawDt = lastMoveNs == 0 ? 8f : (nowNs - lastMoveNs) / 1_000_000f;
                    lastMoveNs = nowNs;
                    float dtMsFull = Math.max(1f, Math.min(1000f, rawDt));

                    // กันเหวี่ยงแรงเกินจนพิกัดกระโดดไกลในเฟรมเดียว
                    // ระบบคงที่: แกน X ไม่ตัดระยะเลย / แกน Y และระบบบิตใช้ 1500
                    final float MAX_FRAME_DELTA = 1500f;
                    final float MAX_FRAME_DELTA_X = viewFlickAccel ? MAX_FRAME_DELTA : 1_000_000f;
                    float dx = Math.max(-MAX_FRAME_DELTA_X, Math.min(MAX_FRAME_DELTA_X, sumDx));
                    float dy = Math.max(-MAX_FRAME_DELTA, Math.min(MAX_FRAME_DELTA, sumDy));

                    // ระบบคงที่เท่านั้น: event แรกหลัง "ช่วงเงียบ" ถ้าก้อนใหญ่เกินเพดานให้ทิ้งทั้งก้อน
                    if (!viewFlickAccel && dtMsFull > POST_GAP_MS) {
                        float capPx = POST_GAP_MAX_V * Math.min(dtMsFull, POST_GAP_CAP_DT_MS);
                        if (Math.abs(dx) > capPx || Math.abs(dy) > capPx) {
                            dx = 0f;
                            dy = 0f;
                        }
                    }

                    // [เพิ่มใหม่] ระบบคงที่เท่านั้น: เพดานความเร็วดิบ สบัดเมาส์แรงแค่ไหนก็ไม่หมุนเร็วเกินเพดาน
                    // (รวมก้อนแรกหลังยกเมาส์วางใหม่ด้วย) ความเร็วที่ต่ำกว่าเพดานไม่ถูกแตะ
                    if (!viewFlickAccel) {
                        final float capDt = Math.max(CONST_CAP_DT_MIN_MS, Math.min(dtMsFull, CONST_CAP_DT_MAX_MS));
                        if (CONST_MAX_RAW_SPEED_X > 0f) {
                            final float capRawX = CONST_MAX_RAW_SPEED_X * capDt;
                            dx = Math.max(-capRawX, Math.min(capRawX, dx));
                        }
                        if (CONST_MAX_RAW_SPEED_Y > 0f) {
                            final float capRawY = CONST_MAX_RAW_SPEED_Y * capDt;
                            dy = Math.max(-capRawY, Math.min(capRawY, dy));
                        }
                    }

                    // ระบบสบัดเมาส์: เฉพาะ "ระบบบิต" เท่านั้น
                    if (viewFlickAccel) {
                        float dtMs = Math.max(1f, Math.min(32f, dtMsFull));

                        // ความเร็วนิ้วเสมือนแกน X (px/ms) หลังคูณความไวแล้ว
                        float fvx = Math.abs(dx * gain * viewSensX) / dtMs;
                        if (fvx > bitRef && bitGain > 0f) {
                            // ระบบบิต: สบัดแรงเร่งเพิ่มเฉพาะแกน X (ไม่เกิน bitMax)
                            float k = Math.min(bitMax, 1f + bitGain * (fvx / bitRef - 1f));
                            dx *= k;
                        }
                    }

                    // ระบบคงที่ใช้โซนความเร็วคงที่เสมอ
                    // ระบบบิตใช้โซนก็ต่อเมื่อเปิด VIEW_CONSTANT_ZONE (ปกติปิด = พฤติกรรมเดิม)
                    final boolean useZone = VIEW_CONSTANT_ZONE || !viewFlickAccel;

                    // ขอให้ระบบส่ง event เมาส์มาทันที ไม่รอรวมตามเฟรม
                    try {
                        mouseLockView.requestUnbufferedDispatch(e);
                    } catch (Exception ignored) {}

                    if (useZone) {
                        // ===== โซนความเร็วคงที่: หมุนต่อเนื่อง ไม่ทิ้งระยะ ไม่ทัชผิดที่ =====
                        final float mvX = dx * gain * viewSensX;

                        // แกน Y
                        viewY += dy * gain * viewSensY;
                        if (VIEW_Y_FREE_IN_ZONE) {
                            // ลากทะลุขอบจอได้ ไม่ยก-วางนิ้วใหม่ (แค่กันเลขลอยไกลเกินจริง)
                            final double yLim = maxScreenHeight * VIEW_Y_LIMIT_SCREENS;
                            if (viewY < -yLim) viewY = -yLim;
                            else if (viewY > maxScreenHeight + yLim) viewY = maxScreenHeight + yLim;
                        }

                        // แกน X ลากทะลุขอบจอได้ ไม่ขึ้นกับ VIEW_Y_FREE_IN_ZONE
                        if (VIEW_X_FREE_IN_ZONE) {
                            // ชดเชยเฉพาะตอนหมุน "ออกนอกจอ" (ตอนหมุนกลับเข้าจอใช้ความเร็วปกติ)
                            final boolean outward = (viewX < 0 && mvX < 0)
                                    || (viewX > maxScreenWidth && mvX > 0);

                            // ปรับความเร็วดิบให้เรียบด้วย EMA ก่อน กันอาการ "สวิง"
                            final float rawSpeedPxMs = Math.abs(dx) / dtMsFull;
                            smoothedLeftSpeedPxMs += (rawSpeedPxMs - smoothedLeftSpeedPxMs) * LEFT_SPEED_SMOOTHING_ALPHA;
                            final float leftF = leftHalfFactor((float) viewX) * leftExtraFactor((float) viewX, smoothedLeftSpeedPxMs);
                            // ตัวคูณตามทิศหันซ้าย (mvX < 0 = เมาส์ไปทางซ้าย)
                            final float dirF = mvX < 0 ? TURN_LEFT_GAIN : 1f;
                            viewX += (outward ? mvX * factorX((float) viewX) : mvX) * leftF * dirF;
                            final double xLim = maxScreenWidth * VIEW_X_LIMIT_SCREENS;
                            if (viewX < -xLim) viewX = -xLim;
                            else if (viewX > maxScreenWidth + xLim) viewX = maxScreenWidth + xLim;

                            // ถ้า Y ตั้งเป็นแบบเดิม (VIEW_Y_FREE_IN_ZONE = false) ให้ Y หลุดโซนแล้วยก-วางใหม่เหมือนเดิม
                            if (!VIEW_Y_FREE_IN_ZONE) {
                                final float yTop = maxScreenHeight * ZONE_Y_MIN;
                                final float yBot = maxScreenHeight * ZONE_Y_MAX;
                                if (viewY < yTop || viewY > yBot) {
                                    final float yNew = viewY > yBot ? yTop + (yBot - yTop) * ZONE_RESTART
                                                                    : yBot - (yBot - yTop) * ZONE_RESTART;
                                    sendViewMoveClamped();
                                    recenterTo((float) Math.max(0.0, Math.min(maxScreenWidth - 1, viewX)), yNew);
                                }
                            }

                            scheduleViewInject();
                            armIdleRecenter();
                            break;
                        }

                        // ===== ด้านล่างนี้ใช้เฉพาะตอน VIEW_X_FREE_IN_ZONE = false (พฤติกรรมเดิมของแกน X) =====
                        final float zx0 = maxScreenWidth * ZONE_X_MIN, zx1 = maxScreenWidth * ZONE_X_MAX;
                        final float zy0 = maxScreenHeight * ZONE_Y_MIN, zy1 = maxScreenHeight * ZONE_Y_MAX;

                        // จุดวางนิ้วใหม่แกน X อยู่ในช่วงปลอดภัยเสมอ (ไม่ทับปุ่ม/ไม่ชิดขอบจอ)
                        final float restartRightX = Math.max(zx0, maxScreenWidth * DOWN_SAFE_X_MIN); // หมุนไปขวา
                        final float restartLeftX = Math.min(zx1, maxScreenWidth * DOWN_SAFE_X_MAX);  // หมุนไปซ้าย

                        // แกน Y หลุดโซนหรือไม่ (ใช้เฉพาะพฤติกรรมเดิม: VIEW_Y_FREE_IN_ZONE = false)
                        boolean yOut = !VIEW_Y_FREE_IN_ZONE && (viewY < zy0 || viewY > zy1);
                        final float newY = viewY > zy1 ? zy0 + (zy1 - zy0) * ZONE_RESTART
                                                       : zy1 - (zy1 - zy0) * ZONE_RESTART;

                        // แกน X: เดินไปทีละช่วง ถ้าชนขอบโซนก็ส่งตำแหน่งที่ขอบ ยก-วางนิ้วใหม่ แล้วเดินต่อด้วยระยะที่เหลือ
                        double remain = mvX;
                        for (int i = 0; i < MAX_ZONE_WRAPS && remain != 0.0; i++) {
                            double target = viewX + remain;
                            if (target >= zx0 && target <= zx1) {
                                viewX = target;
                                remain = 0.0;
                                break;
                            }
                            boolean toRight = target > zx1;
                            double edge = toRight ? zx1 : zx0;
                            remain -= (edge - viewX); // ระยะที่เกินขอบ เก็บไว้ทำต่อ
                            viewX = edge;

                            if (VIEW_Y_FREE_IN_ZONE) {
                                sendViewMoveRawY();
                                recenterTo(toRight ? restartRightX : restartLeftX, (float) viewY, true);
                            } else {
                                sendViewMoveClamped();
                                float ry = yOut ? newY : (float) viewY;
                                yOut = false; // แกน Y ถูกจัดการพร้อมกันแล้ว
                                recenterTo(toRight ? restartRightX : restartLeftX, ry);
                            }
                        }
                        // ถ้าครบรอบสูงสุดแล้วยังเหลือ (สบัดแรงผิดปกติ) ทิ้งส่วนที่เกิน
                        if (remain != 0.0) {
                            double target = viewX + remain;
                            viewX = Math.max(zx0, Math.min(zx1, target));
                        }

                        // แกน Y หลุดโซนโดยที่ X ไม่ชนขอบ: ยก-วางใหม่เหมือนเดิม
                        if (yOut) {
                            sendViewMoveClamped();
                            recenterTo((float) viewX, newY);
                        }

                        // ส่งตำแหน่งล่าสุดต่อทันทีทุกกรณี (รวมหลังวางนิ้วใหม่)
                        scheduleViewInject();
                        armIdleRecenter();
                        break;
                    }

                    // ===== ระบบบิต / พฤติกรรมเดิม (ไม่ได้แก้) =====
                    viewX += dx * gain * viewSensX * factorX((float) viewX);
                    // แกน Y ชดเชยตอนหลุดขอบจอ (สูตรเดิม)
                    viewY += dy * gain * viewSensY * offscreenFactorY((float) viewY, maxScreenHeight, OFFSCREEN_GAIN_Y);

                    armIdleRecenter();

                    final float startX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                    final float startY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;

                    boolean needRecenter;
                    if (VIEW_ALLOW_OFFSCREEN) {
                        needRecenter = false;
                    } else {
                        viewX = Math.max(10f, Math.min(maxScreenWidth - 10f, viewX));
                        viewY = Math.max(10f, Math.min(maxScreenHeight - 10f, viewY));
                        needRecenter =
                                Math.abs(viewX - startX) > maxScreenWidth * VIEW_RANGE_X
                                || Math.abs(viewY - startY) > maxScreenHeight * VIEW_RANGE_Y
                                || viewX <= 40f || viewX >= maxScreenWidth - 40f
                                || viewY <= 40f || viewY >= maxScreenHeight - 40f;
                    }

                    if (needRecenter && !pressing) {
                        if (mouseMoveToken != 0) {
                            TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) viewX), Math.round((float) viewY));
                        }
                        viewX = startX;
                        viewY = startY;
                        mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
                    } else {
                        scheduleViewInject();
                    }
                } else {
                    // ===== โหมดเคอร์เซอร์ปกติ =====
                    cursorX += sumDx * gain;
                    cursorY += sumDy * gain;
                    cursorX = Math.max(0f, Math.min(maxScreenWidth - 1, cursorX));
                    cursorY = Math.max(0f, Math.min(maxScreenHeight - 1, cursorY));

                    // 1) ขอ event เมาส์ทันที ไม่รอรวมตามเฟรมจอ
                    try {
                        mouseLockView.requestUnbufferedDispatch(e);
                    } catch (Exception ignored) {}

                    // 2) ขยับเคอร์เซอร์ทันที ไม่รอ Choreographer
                    if (customCursorView != null) {
                        customCursorView.setTranslationX(cursorX);
                        customCursorView.setTranslationY(cursorY);
                    }

                    boolean uiHandled = eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_MOVE);
                    if (!uiHandled && mouseMoveToken != 0) {
                        // 3) ส่งพิกัดทศนิยม ลากแล้วไม่เป็นขั้นบันได
                        TouchSimulator.simulateMoveF(mouseMoveToken, 2, cursorX, cursorY);
                    }
                }
                break;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_DOWN:
                // คลิกขวา: ACTION_DOWN ของเมาส์จะมี buttonState ขวา (actionButton เป็น 0) ส่วน BUTTON_PRESS มี actionButton ขวา
                // ถ้า actionButton ระบุปุ่มมาชัดเจน (เช่น ซ้าย) ให้ตัดสินจาก actionButton เท่านั้น
                final int ab = e.getActionButton();
                if (ab == MotionEvent.BUTTON_SECONDARY
                        || (ab == 0 && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0)) {
                    if (!rightDown) {
                        rightDown = true;
                        eventListener.onRightClick();
                    }
                    break;
                }
                // ล็อคเมาส์อยู่: กดซ้ายลง -> FloatingService กดค้างที่ปุ่มซึ่งตั้งเป็น MS_L
                if (isMouseMode) {
                    if (!pressing) {
                        pressing = true;
                        eventListener.onMouseLockedLeftDown();
                    }
                    break;
                }
                if (!isMouseMode && !pressing) {
                    pressing = true;
                    if (!eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_DOWN)) {
                        mouseMoveToken = TouchSimulator.simulateDown(2, (int) cursorX, (int) cursorY);
                    }
                }
                break;

            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_UP:
                if (isMouseMode && pressing && e.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
                    pressing = false;
                    // ปล่อยนิ้วที่กดค้างไว้
                    eventListener.onMouseLockedLeftUp();
                    break;
                }
                if (!isMouseMode && pressing && e.getActionButton() != MotionEvent.BUTTON_SECONDARY) {
                    pressing = false;
                    eventListener.onMouseClickUI(cursorX, cursorY, MotionEvent.ACTION_UP);
                    if (mouseMoveToken != 0) {
                        TouchSimulator.simulateUp(mouseMoveToken, 2, (int) cursorX, (int) cursorY);
                        mouseMoveToken = 0;
                    }
                }
                break;
        }
    }

    public void toggleMouseMode() {
        setMouseMode(!isMouseMode);
    }

    public void setMouseMode(boolean active) {
        if (destroyed) return;
        refreshScreenSize();
        boolean wasMouseMode = isMouseMode;
        isMouseMode = active;
        // กันนิ้วคลิกซ้ายค้างตอนสลับโหมด
        if (pressing && wasMouseMode) eventListener.onMouseLockedLeftUp();
        pressing = false;
        if (!isPaused) {
            mouseLockView.setVisibility(View.VISIBLE);
            if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
        }

        // เรียก updateViewLayout (binder call) เฉพาะเมื่อ flag ยังไม่ถูกตั้งจริงๆ
        if (mouseLockParams != null && windowManager != null
                && (mouseLockParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) {
            mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
            } catch (Exception ignored) {}
        }

        if (isMouseMode) {
            if (!wasMouseMode) {
                // เริ่มโหมดมุมมองใหม่ รีเซ็ตตัวจับเวลาของระบบบิต
                lastMoveNs = 0;
                // รีเซ็ตความเร็วที่ปรับเรียบแล้วของฝั่งซ้าย กันค่าเก่าค้างมา
                smoothedLeftSpeedPxMs = 0f;
                // รีเซ็ตตัวนับ flicker ของการเช็ค isPointerDown
                lostPointerStreak = 0;

                if (mouseMoveToken != 0) {
                    TouchSimulator.simulateUp(mouseMoveToken, 2, (int) cursorX, (int) cursorY);
                    mouseMoveToken = 0;
                }
                viewX = hasCustomTarget ? targetBtnX : maxScreenWidth * 0.75f;
                viewY = hasCustomTarget ? targetBtnY : maxScreenHeight * 0.5f;

                mouseMoveToken = TouchSimulator.simulateDown(2, Math.round((float) viewX), Math.round((float) viewY));
            }

            // แค่ซ่อนเคอร์เซอร์ ไม่ลบ view ทิ้ง (ครั้งหน้าไม่ต้องสร้าง/โหลดรูปใหม่)
            if (customCursorView != null) customCursorView.setVisibility(View.GONE);

            if (!typingMode) requestCaptureFast();
        } else {
            // ออกจากโหมดมุมมอง ยกเลิกงานส่งตำแหน่งที่ค้างอยู่
            if (wasMouseMode) cancelViewInject();

            if (wasMouseMode && mouseMoveToken != 0) {
                TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) viewX), Math.round((float) viewY));
                mouseMoveToken = 0;
            }

            // ปลดล็อคเมาส์ -> เคอร์เซอร์กลับมาอยู่กลางจอ
            if (wasMouseMode) {
                cursorX = maxScreenWidth / 2f;
                cursorY = maxScreenHeight / 2f;
            }

            // สร้างเคอร์เซอร์เฉพาะครั้งแรก หลังจากนั้นใช้ตัวเดิมซ้ำ
            if (customCursorView == null) {
                customCursorView = new ImageView(context);
                int cursorId = context.getResources().getIdentifier("ic_cursor", "drawable", context.getPackageName());

                if (cursorId != 0) {
                    customCursorView.setImageResource(cursorId);
                } else {
                    GradientDrawable fallbackCursor = new GradientDrawable();
                    fallbackCursor.setShape(GradientDrawable.OVAL);
                    fallbackCursor.setColor(Color.parseColor("#FF5555"));
                    fallbackCursor.setStroke(4, Color.WHITE);
                    customCursorView.setBackground(fallbackCursor);
                }

                FrameLayout.LayoutParams cursorParams = new FrameLayout.LayoutParams(cursorSize, cursorSize);
                cursorParams.gravity = Gravity.TOP | Gravity.START;
                customCursorView.setLayoutParams(cursorParams);
                cursorLayer.addView(customCursorView);
            }

            customCursorView.setVisibility(typingMode ? View.GONE : View.VISIBLE);
            customCursorView.setTranslationX(cursorX);
            customCursorView.setTranslationY(cursorY);

            if (!typingMode) requestCaptureFast();
        }
    }

    // โหมดพิมพ์: ปล่อย pointer capture + ให้ overlay ไม่รับโฟกัส เกมจะได้รับแป้นตรงๆ
    public void setTypingMode(boolean typing) {
        if (destroyed || typingMode == typing || mouseLockView == null) return;
        typingMode = typing;
        injectHandler.removeCallbacks(captureRunnable);
        try {
            if (typing) {
                cancelViewInject();
                injectHandler.removeCallbacks(idleRecenterRunnable);
                idlePending = false;
                releaseMouseState();
                if (isMouseMode) setMouseMode(false);
                if (cursorLayer != null) cursorLayer.setVisibility(View.GONE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mouseLockView.releasePointerCapture();
                mouseLockView.clearFocus();
                mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mouseLockParams.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
            } else {
                mouseLockParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mouseLockParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                windowManager.updateViewLayout(mouseLockView, mouseLockParams);
                if (!isPaused) {
                    if (cursorLayer != null) cursorLayer.setVisibility(View.VISIBLE);
                    if (customCursorView != null && !isMouseMode) customCursorView.setVisibility(View.VISIBLE);
                    requestCaptureFast();
                }
            }
        } catch (Exception ignored) {}
    }

    // ยกหน้าต่างเคอร์เซอร์ขึ้นบนสุด (เรียกหลังมีหน้าต่างใหม่เด้งขึ้นมา)
    public void bringCursorToFront() {
        if (destroyed || cursorLayer == null || windowManager == null) return;
        try {
            if (cursorLayer.getParent() != null) windowManager.removeViewImmediate(cursorLayer);
            windowManager.addView(cursorLayer, cursorLayerParams);
        } catch (Exception ignored) {}
    }

    public boolean isMouseActive() {
        return isMouseMode;
    }

    public void releaseMouseState() {
        rightDown = false;
        prevSecondary = false;
        lostPointerStreak = 0;
        // ปล่อยนิ้วคลิกซ้ายที่กดค้างอยู่ (ถ้ามี)
        if (pressing && isMouseMode) {
            pressing = false;
            eventListener.onMouseLockedLeftUp();
        }
        if (mouseMoveToken != 0) {
            TouchSimulator.simulateUp(mouseMoveToken, 2, Math.round((float) viewX), Math.round((float) viewY));
            mouseMoveToken = 0;
        }
    }

    public void updateSensitivity(float sensitivity) {
        this.mouseSensitivity = sensitivity;
    }

    public void updateCursorSize(int size) {
        this.cursorSize = size;
        if (customCursorView != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) customCursorView.getLayoutParams();
            params.width = cursorSize;
            params.height = cursorSize;
            customCursorView.setLayoutParams(params);
        }
    }

    public void destroy() {
        destroyed = true;
        cancelViewInject();
        injectHandler.removeCallbacks(idleRecenterRunnable);
        idlePending = false;
        injectHandler.removeCallbacks(captureRunnable);
        // ไม่ inject UP ตรงนี้ (กัน main thread ค้างตอนปิด) FloatingService จะ CANCEL ทุกนิ้วให้เอง
        mouseMoveToken = 0;
        try {
            if (mouseLockView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mouseLockView.setOnCapturedPointerListener(null);
                    mouseLockView.releasePointerCapture();
                }
                mouseLockView.clearFocus();
            }
        } catch (Exception ignored) {}
        try {
            if (cursorLayer != null && cursorLayer.getParent() != null) {
                windowManager.removeView(cursorLayer);
            }
        } catch (Exception ignored) {}
        try {
            if (mouseLockView != null && mouseLockView.getParent() != null) {
                windowManager.removeView(mouseLockView);
            }
        } catch (Exception ignored) {}
    }
}
