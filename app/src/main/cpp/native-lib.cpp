#include <jni.h>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <linux/uinput.h>
#include <cstring>
#include <android/log.h>

#define TAG "VirtualInputNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int touch_fd = -1;
static int mouse_fd = -1;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mynovo_mynovo1_VirtualInputDriver_nativeInitTouch(JNIEnv *env, jclass clazz) {
    if (touch_fd >= 0) return true;
    
    touch_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (touch_fd < 0) return false;

    ioctl(touch_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(touch_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(touch_fd, UI_SET_KEYBIT, BTN_TOUCH);
    
    ioctl(touch_fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(touch_fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(touch_fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(touch_fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "BlueMouse-Virtual-Touch");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;

    uidev.absmin[ABS_MT_POSITION_X] = 0;
    uidev.absmax[ABS_MT_POSITION_X] = 2400; 
    uidev.absmin[ABS_MT_POSITION_Y] = 0;
    uidev.absmax[ABS_MT_POSITION_Y] = 1080; 
    uidev.absmin[ABS_MT_TRACKING_ID] = 0;
    uidev.absmax[ABS_MT_TRACKING_ID] = 65535;

    write(touch_fd, &uidev, sizeof(uidev));
    if (ioctl(touch_fd, UI_DEV_CREATE) < 0) {
        close(touch_fd);
        touch_fd = -1;
        return false;
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mynovo_mynovo1_VirtualInputDriver_nativeInitMouse(JNIEnv *env, jclass clazz) {
    if (mouse_fd >= 0) return true;

    mouse_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (mouse_fd < 0) return false;

    ioctl(mouse_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(mouse_fd, UI_SET_EVBIT, EV_REL);
    ioctl(mouse_fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(mouse_fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(mouse_fd, UI_SET_RELBIT, REL_X);
    ioctl(mouse_fd, UI_SET_RELBIT, REL_Y);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "BlueMouse-Virtual-Mouse");
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor = 0x1234;
    uidev.id.product = 0x5679;
    uidev.id.version = 1;

    write(mouse_fd, &uidev, sizeof(uidev));
    if (ioctl(mouse_fd, UI_DEV_CREATE) < 0) {
        close(mouse_fd);
        mouse_fd = -1;
        return false;
    }
    return true;
}

static void emit(int fd, int type, int code, int val) {
    struct input_event ie;
    memset(&ie, 0, sizeof(ie));
    ie.type = type;
    ie.code = code;
    ie.value = val;
    gettimeofday(&ie.time, NULL);
    write(fd, &ie, sizeof(ie));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mynovo_mynovo1_VirtualInputDriver_nativeInjectTouch(JNIEnv *env, jclass clazz, jint slot, jint trackingId, jint x, jint y, jboolean down) {
    if (touch_fd < 0) return;

    emit(touch_fd, EV_ABS, ABS_MT_SLOT, slot);
    emit(touch_fd, EV_ABS, ABS_MT_TRACKING_ID, down ? trackingId : -1);
    if (down) {
        emit(touch_fd, EV_ABS, ABS_MT_POSITION_X, x);
        emit(touch_fd, EV_ABS, ABS_MT_POSITION_Y, y);
    }
    emit(touch_fd, EV_SYN, SYN_REPORT, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mynovo_mynovo1_VirtualInputDriver_nativeInjectMouseMove(JNIEnv *env, jclass clazz, jint dx, jint dy) {
    if (mouse_fd < 0) return;

    emit(mouse_fd, EV_REL, REL_X, dx);
    emit(mouse_fd, EV_REL, REL_Y, dy);
    emit(mouse_fd, EV_SYN, SYN_REPORT, 0);
} 