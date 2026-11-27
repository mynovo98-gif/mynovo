cmake_minimum_required(VERSION 3.10.2)

project("mynovo" C CXX)

add_library(native-lib SHARED
        native-lib.cpp
        uinput_driver.c)

find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})

# ===== ตัวช่วยจับคีย์บอร์ด (executable) =====
# ตั้งชื่อผลลัพธ์เป็น libkbgrab.so เพื่อให้ Gradle แพ็กลง lib/<abi>/
# แล้ว activate.sh ค่อยคัดลอกไป /data/local/tmp/mynovo_kbgrab
add_executable(kbgrab kbgrab.c)

set_target_properties(kbgrab PROPERTIES
        OUTPUT_NAME "kbgrab"
        PREFIX "lib"
        SUFFIX ".so"
        # ให้ executable ไปอยู่โฟลเดอร์เดียวกับไลบรารี เพื่อให้ถูกแพ็กเข้า APK
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
        POSITION_INDEPENDENT_CODE ON)

target_link_options(kbgrab PRIVATE -pie)
