cmake_minimum_required(VERSION 3.10.2)

project("native-lib")

# คอมไพล์โค้ด C++ ให้เป็น Shared Library (.so) สำหรับเรียกใช้งานผ่าน Java JNI
add_library(
    native-lib
    SHARED
    native-lib.cpp
)

# ค้นหาและเชื่อมต่อไลบรารี Log ของ Android สำหรับพ่น Logcat
find_library(
    log-lib
    log
)

target_link_libraries(
    native-lib
    ${log-lib}
)