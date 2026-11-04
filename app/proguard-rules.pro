# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ==== เอา -keep class com.mynovo.mynovo1.** ออกแล้ว ====
# ตอนนี้คลาสทั่วไปในแอปจะถูกสับชื่อ/ลบโค้ดที่ไม่ได้ใช้ ป้องกันโค้ดได้เต็มที่
# เหลือ -keep เฉพาะจุดที่จำเป็นจริงๆ ด้านล่างนี้เท่านั้น

# FloatingService ถูกเรียกจากระบบ Android (Service lifecycle) ผ่านชื่อเต็มใน AndroidManifest
# ต้องกันไว้ ไม่งั้นระบบหาคลาสไม่เจอตอนเปิด service
-keep class com.mynovo.mynovo1.FloatingService { *; }

# MainActivity ถูกเรียกจากระบบผ่าน AndroidManifest เหมือนกัน
-keep class com.mynovo.mynovo1.MainActivity { *; }

# interface callback ที่เรียกข้าม object (MouseController.MouseEventListener)
# ถ้าไม่กัน method name อาจถูกสับจนเรียกไม่ตรงกัน
-keep interface com.mynovo.mynovo1.MouseController$MouseEventListener { *; }

# IKeymapperService + AIDL stub/proxy ที่ generate อัตโนมัติ (ใช้ข้าม process/Shizuku)
-keep interface com.mynovo.mynovo1.IKeymapperService { *; }
-keep class com.mynovo.mynovo1.IKeymapperService$* { *; }
-keep class **.I*$Stub { *; }
-keep class **.I*$Stub$Proxy { *; }

# native-lib.cpp (JNI): ห้ามเปลี่ยนชื่อคลาส/เมธอดที่ native code เรียกกลับมา ไม่งั้น UnsatisfiedLinkError
-keepclasseswithmembernames class * {
    native <methods>;
}
# ถ้า native-lib.cpp เรียกเมธอดใน MouseController/TouchSimulator/VirtualInputDriver กลับเข้ามาโดยตรง (JNI callback)
# ให้ปลดคอมเมนต์บรรทัดที่เกี่ยวข้องด้านล่าง (เผื่อไว้ก่อน เปิดใช้ถ้า build แล้วพังเรื่อง native)
#-keep class com.mynovo.mynovo1.MouseController { *; }
#-keep class com.mynovo.mynovo1.TouchSimulator { *; }
#-keep class com.mynovo.mynovo1.VirtualInputDriver { *; }

# Shizuku ต้องกันทั้งหมด (ใช้ AIDL + reflection ภายใน)
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# AppCompat / Material อาจมี reflection ภายใน กันไว้กันพลาด
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**

# เก็บ line number ไว้ช่วย debug stack trace ตอน release พัง (เปิดไว้ก่อนช่วงทดสอบ)
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# ปิดใช้งานตอนปล่อยจริงแล้วอยากซ่อนชื่อไฟล์ต้นฉบับ (ตอนนี้ปิดไว้เพื่อให้ debug ง่ายก่อน)
#-renamesourcefileattribute SourceFile
