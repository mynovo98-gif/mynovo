#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <linux/uinput.h>

// ฟังก์ชันสำหรับส่ง Event (กด/ปล่อยปุ่ม) ลงไประบบ
void emit(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    write(fd, &ie, sizeof(ie));
}

int main(void) {
    int fd;
    struct uinput_user_dev uud;

    // เปิดไฟล์ /dev/uinput (ต้องมีสิทธิ์ Root หรือรันผ่าน Shizuku)
    fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "Error: Cannot open /dev/uinput. (Need Shizuku/Root)\n");
        return -1;
    }

    // เปิดใช้งานโหมดปุ่มกด (Key Events)
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    
    // ลงทะเบียนปุ่มที่ต้องการใช้ (เหลือเฉพาะ W, A, S, D สำหรับจอยสติ๊ก)
    ioctl(fd, UI_SET_KEYBIT, KEY_W);
    ioctl(fd, UI_SET_KEYBIT, KEY_A);
    ioctl(fd, UI_SET_KEYBIT, KEY_S);
    ioctl(fd, UI_SET_KEYBIT, KEY_D);

    memset(&uud, 0, sizeof(uud));
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "BlueMousePro_Gamepad");
    uud.id.bustype = BUS_USB;
    uud.id.vendor  = 0x1234;
    uud.id.product = 0x5678;
    uud.id.version = 1;

    write(fd, &uud, sizeof(uud));
    
    // สร้าง Virtual Device สำเร็จ!
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "Error: Cannot create uinput device.\n");
        close(fd);
        return -1;
    }

    // Loop รอรับคำสั่งจาก Java ผ่าน Standard Input (stdin)
    int code, value;
    while (scanf("%d %d", &code, &value) == 2) {
        emit(fd, EV_KEY, code, value);       // ส่งสถานะปุ่ม
        emit(fd, EV_SYN, SYN_REPORT, 0);       // ยืนยันคำสั่ง (Sync)
        fflush(stdout);
    }

    // เมื่อ Java ปิดการทำงาน
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}