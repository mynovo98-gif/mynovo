# ==== Hardened R8 rules ====
# หลักการ: keep เฉพาะที่จำเป็นจริง ๆ และใช้ rule แคบที่สุดเท่าที่ทำได้

# ---------- เพิ่มความแรงของ obfuscation ----------
# ย้ายทุกคลาสไปแพ็กเกจรากเดียว ทำให้โครงสร้างโปรเจกต์หายไป
-repackageclasses ''
# อนุญาตให้ R8 ปรับ access modifier เพื่อ inline/ย่อโค้ดได้มากขึ้น
-allowaccessmodification
# ผ่านการ optimize หลายรอบ
-optimizationpasses 5

# ---------- ลบ Log ออกจาก release ----------
# กัน log หลุดข้อมูลภายใน (ชื่อเมธอด, state, ข้อมูล ADB pairing ฯลฯ)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# ---------- Stack trace ----------
# เก็บเลขบรรทัดไว้ แต่ซ่อนชื่อไฟล์ต้นฉบับ (ใช้ mapping.txt แปลกลับตอน debug)
-keepattributes LineNumberTable
-renamesourcefileattribute ''

# ---------- Android components ----------
# AGP สร้าง keep rule ให้ Activity/Service ที่อยู่ใน AndroidManifest อัตโนมัติ
# จึงไม่ต้อง keep FloatingService / MainActivity แบบ { *; } อีก (ชื่อเมธอดภายในจะถูกสับได้)

# ---------- AIDL (ข้าม process / Shizuku) ----------
# จำกัดเฉพาะแพ็กเกจของเรา ไม่ใช้ wildcard **.I*$Stub ที่กว้างเกินไป
-keep interface com.mynovo.mynovo1.IKeymapperService { *; }
-keep class com.mynovo.mynovo1.IKeymapperService$Stub { *; }
-keep class com.mynovo.mynovo1.IKeymapperService$Stub$Proxy { *; }

# ---------- JNI ----------
# ชื่อ native method ต้องคงไว้
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# ถ้า native-lib.cpp เรียกกลับเข้า Java ให้เปิดเฉพาะเมธอดที่ถูกเรียกจริง
# (แคบกว่า keep ทั้งคลาส) เช่น:
#-keepclassmembers class com.mynovo.mynovo1.MouseController {
#    void onNativeCallback(int, int);
#}

# ---------- InputServer (app_process) ----------
# keep แค่ main() ไม่ต้อง keep สมาชิกอื่น
-keep class com.mynovo.mynovo1.InputServer {
    public static void main(java.lang.String[]);
}

# ---------- ไลบรารีภายนอก ----------
# Shizuku มี consumer rules ในตัว ลองไม่ใส่ก่อน ถ้าพังค่อยเปิด
#-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# libadb-android: คงไว้ก่อน (ใช้ reflection) ทดสอบแล้วค่อยตัดให้แคบลง
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**

# BouncyCastle: keep เฉพาะ provider ไม่ใช่ทั้งไลบรารี
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# Conscrypt
-keep class org.conscrypt.OpenSSLProvider { *; }
-dontwarn org.conscrypt.**

# AdbManager ถูกอ้างจากโค้ดตรง ๆ ไม่ต้อง keep แล้ว R8 จะสับชื่อให้
# AppCompat/Material: ลบ keep ออก ใช้ consumer rules ของไลบรารีเอง
