// kbgrab.c - ตัวช่วยจับคีย์บอร์ดแบบ exclusive (EVIOCGRAB)
// รันด้วยสิทธิ์ shell โดย InputServer (คัดลอกไป /data/local/tmp/mynovo_kbgrab ผ่าน activate.sh)
//
// คำสั่งทาง stdin (1 ตัวอักษร): G = เริ่ม grab U = ปล่อย grab Q = ออก
// ผลลัพธ์ทาง stdout (ข้อความทีละบรรทัด):
// H <n> ตอนเริ่ม: เจอคีย์บอร์ดกี่ตัว
// S <n> จำนวนคีย์บอร์ดที่ grab อยู่ตอนนี้ (0 = grab ไม่สำเร็จ/ไม่มี)
// K <code> <v> ปุ่ม Linux scancode, v = 0 ปล่อย / 1 กด / 2 ค้าง(repeat) (ส่งเฉพาะตอน grab อยู่)
// R ผู้ใช้กด Ctrl+Esc = ปล่อย grab ทั้งหมด (ทางหนีไฟ)
// [MOUSE] M <dx> <dy> เมาส์เลื่อน (REL_X/REL_Y รวมต่อ 1 SYN_REPORT) ส่งเฉพาะตอน grab อยู่
// [MOUSE] B <code> <v> ปุ่มเมาส์ (BTN_LEFT=272, BTN_RIGHT=273, ...) v = 0 ปล่อย / 1 กด (ส่งเฉพาะตอน grab อยู่)
//
// ถ้า stdin ปิด (InputServer ตาย/ถูกปิด) ตัวนี้จะออกเอง และ kernel จะปล่อย grab ให้อัตโนมัติ

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdalign.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/inotify.h>
#include <linux/input.h>

#define MAX_DEV 16
#define TB(b, a) (((a)[(b) / 8] >> ((b) % 8)) & 1)

// [แก้] 0 = ไม่ grab เมาส์ (เมาส์ยังไปทางระบบตามปกติ ใช้กับเกม/DeX ได้)
//       1 = grab เมาส์ด้วยและส่งเฟรม M/B ให้แอป (ต้องมีโค้ดฝั่งแอปรับเฟรมเมาส์ไปขยับเคอร์เซอร์เอง
//           ไม่งั้นพอ grab เมาส์จะตายสนิท)
// ตอนนี้ FloatingService ยังไม่รับเฟรมเมาส์ จึงตั้งเป็น 0
#ifndef GRAB_MOUSE
#define GRAB_MOUSE 0
#endif

#ifndef EVIOCGRAB
#define EVIOCGRAB _IOW('E', 0x90, int)
#endif

typedef struct {
    int fd;
    int g; // grab อยู่หรือไม่
    int rw; // เปิดแบบเขียนได้หรือไม่ (ใช้สั่งไฟ LED)
    int kb; // [MOUSE] เป็นคีย์บอร์ดหรือไม่
    int ms; // [MOUSE] เป็นเมาส์หรือไม่
    int dx, dy; // [MOUSE] ค่าเลื่อนสะสมก่อนถึง SYN_REPORT
    char path[64];
} Dev;

static Dev devs[MAX_DEV];
static int ndev = 0;
static int want = 0; // ผู้ใช้สั่งให้ grab อยู่หรือไม่
static int ctrlDown = 0;

static void say(const char *s) {
    size_t l = strlen(s);
    while (l > 0) {
        ssize_t w = write(1, s, l);
        if (w < 0) {
            if (errno == EINTR) continue;
            _exit(0); // InputServer ปิดท่อแล้ว
        }
        s += w;
        l -= (size_t) w;
    }
}

// สั่งเปิด/ดับไฟ Caps Lock ของคีย์บอร์ดตัวนี้ (ทำได้เฉพาะที่เปิดแบบ O_RDWR)
static void set_caps_led(const Dev *d, int on) {
    if (!d->rw) return;
    if (!d->kb) return; // [MOUSE] เมาส์ไม่มีไฟ Caps
    struct input_event ev[2];
    memset(ev, 0, sizeof ev);
    ev[0].type = EV_LED;
    ev[0].code = LED_CAPSL;
    ev[0].value = on ? 1 : 0;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;
    ssize_t r = write(d->fd, ev, sizeof ev);
    (void) r;
}

// [MOUSE] นับเฉพาะคีย์บอร์ด เพื่อให้ความหมายของ H / S เหมือนเดิม
static int grabbed_count(void) {
    int n = 0;
    for (int i = 0; i < ndev; i++) if (devs[i].g && devs[i].kb) n++;
    return n;
}

static int keyboard_count(void) {
    int n = 0;
    for (int i = 0; i < ndev; i++) if (devs[i].kb) n++;
    return n;
}

static void report_state(void) {
    char b[32];
    snprintf(b, sizeof b, "S %d\n", grabbed_count());
    say(b);
}

static int is_keyboard(int fd) {
    unsigned char ev[(EV_MAX + 7) / 8 + 1];
    unsigned char key[(KEY_MAX + 7) / 8 + 1];
    memset(ev, 0, sizeof ev);
    memset(key, 0, sizeof key);
    if (ioctl(fd, EVIOCGBIT(0, sizeof ev), ev) < 0) return 0;
    if (!TB(EV_KEY, ev)) return 0;
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key) < 0) return 0;
    // ต้องมี A, Z, SPACE, ENTER ถึงนับเป็นคีย์บอร์ดจริง (ไม่แย่งปุ่มวอลุ่ม/เพาเวอร์/เมาส์)
    return TB(KEY_A, key) && TB(KEY_Z, key) && TB(KEY_SPACE, key) && TB(KEY_ENTER, key);
}

// [MOUSE] เมาส์จริง: มีแกนสัมพัทธ์ X/Y และปุ่มซ้าย
static int is_mouse(int fd) {
    unsigned char ev[(EV_MAX + 7) / 8 + 1];
    unsigned char rel[(REL_MAX + 7) / 8 + 1];
    unsigned char key[(KEY_MAX + 7) / 8 + 1];
    memset(ev, 0, sizeof ev);
    memset(rel, 0, sizeof rel);
    memset(key, 0, sizeof key);
    if (ioctl(fd, EVIOCGBIT(0, sizeof ev), ev) < 0) return 0;
    if (!TB(EV_REL, ev) || !TB(EV_KEY, ev)) return 0;
    if (ioctl(fd, EVIOCGBIT(EV_REL, sizeof rel), rel) < 0) return 0;
    if (!TB(REL_X, rel) || !TB(REL_Y, rel)) return 0;
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof key), key) < 0) return 0;
    return TB(BTN_LEFT, key);
}

static void add_dev(const char *path) {
    if (ndev >= MAX_DEV) return;
    for (int i = 0; i < ndev; i++) {
        if (strcmp(devs[i].path, path) == 0) return;
    }
    // ลองเปิดแบบอ่าน+เขียนก่อน (เพื่อสั่งไฟ LED) ถ้าไม่ได้ค่อยถอยกลับเป็นอ่านอย่างเดียว
    int rw = 1;
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        rw = 0;
        fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    }
    if (fd < 0) return;
    int kb = is_keyboard(fd);
    int ms = GRAB_MOUSE ? is_mouse(fd) : 0;

    if (!kb && !ms) {
        close(fd);
        return;
    }
    Dev *d = &devs[ndev];
    d->fd = fd;
    d->g = 0;
    d->rw = rw;
    d->kb = kb;
    d->ms = ms;
    d->dx = 0;
    d->dy = 0;
    snprintf(d->path, sizeof d->path, "%s", path);
    ndev++;
    if (want && ioctl(fd, EVIOCGRAB, 1) == 0) {
        d->g = 1;
        set_caps_led(d, 0); // คีย์บอร์ดที่เสียบใหม่ตอนกำลัง grab ให้ดับไฟด้วย
    }
}

static void remove_dev(int i) {
    close(devs[i].fd);
    devs[i] = devs[ndev - 1];
    ndev--;
}

static void scan_devices(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return;
    struct dirent *e;
    char path[64];
    while ((e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        add_dev(path);
    }
    closedir(dir);
}

static void set_grab(int on) {
    want = on;
    for (int i = 0; i < ndev; i++) {
        if (on && !devs[i].g) {
            if (ioctl(devs[i].fd, EVIOCGRAB, 1) == 0) {
                devs[i].g = 1;
                devs[i].dx = 0; // [MOUSE]
                devs[i].dy = 0;
                set_caps_led(&devs[i], 0); // เริ่ม grab: ดับไฟ Caps
            }
        } else if (!on && devs[i].g) {
            set_caps_led(&devs[i], 0); // ปล่อย grab: ดับไฟ Caps ก่อนคืนให้ระบบ
            ioctl(devs[i].fd, EVIOCGRAB, 0);
            devs[i].g = 0;
            devs[i].dx = 0; // [MOUSE]
            devs[i].dy = 0;
            set_caps_led(&devs[i], 0); // ย้ำอีกรอบหลังปล่อย
        }
    }
    report_state();
}

static void handle_key(int code, int value) {
    // [แก้] ย้ายการติดตามสถานะ Ctrl ไปที่ read_dev() แล้ว (ทำงานแม้ไม่ได้ grab)

    // ทางหนีไฟ: Ctrl+Esc = ปล่อย grab ทั้งหมด (คีย์บอร์ดกลับไปทำงานกับระบบตามปกติ)
    if (code == KEY_ESC && value == 1 && ctrlDown) {
        set_grab(0);
        say("R\n");
        return;
    }
    char b[48];
    snprintf(b, sizeof b, "K %d %d\n", code, value);
    say(b);
}

// คืน 0 ถ้าอุปกรณ์หาย/อ่านไม่ได้ (ให้ลบออก)
static int read_dev(int i) {
    struct input_event ev[32];
    ssize_t n = read(devs[i].fd, ev, sizeof ev);
    if (n < 0) {
        if (errno == EAGAIN || errno == EINTR) return 1;
        return 0;
    }
    if (n == 0) return 0;
    int cnt = (int) (n / (ssize_t) sizeof(struct input_event));
    for (int k = 0; k < cnt; k++) {
        // [MOUSE] เมาส์เลื่อน: สะสมไว้จนถึง SYN_REPORT แล้วส่งเป็นบรรทัดเดียว
        if (ev[k].type == EV_REL) {
            if (devs[i].ms && devs[i].g) {
                if (ev[k].code == REL_X) devs[i].dx += ev[k].value;
                else if (ev[k].code == REL_Y) devs[i].dy += ev[k].value;
            }
            continue;
        }
        if (ev[k].type == EV_SYN) {
            if (ev[k].code == SYN_REPORT && devs[i].ms) {
                if (devs[i].g && (devs[i].dx != 0 || devs[i].dy != 0)) {
                    char b[48];
                    snprintf(b, sizeof b, "M %d %d\n", devs[i].dx, devs[i].dy);
                    say(b);
                }
                devs[i].dx = 0;
                devs[i].dy = 0;
            }
            continue;
        }

        if (ev[k].type != EV_KEY) continue;
        if (ev[k].value < 0 || ev[k].value > 2) continue;

        // [MOUSE] ปุ่มเมาส์ (BTN_MOUSE..BTN_TASK) ส่งเฉพาะตอน grab อยู่ และไม่ส่ง repeat
        if (devs[i].ms && ev[k].code >= BTN_MOUSE && ev[k].code <= BTN_TASK) {
            if (devs[i].g && ev[k].value != 2) {
                char b[48];
                snprintf(b, sizeof b, "B %d %d\n", ev[k].code, ev[k].value);
                say(b);
            }
            continue;
        }

        if (!devs[i].kb) continue; // [MOUSE] อุปกรณ์ที่ไม่ใช่คีย์บอร์ด ไม่เข้าทางคีย์

        // [แก้] ติดตามสถานะ Ctrl ตลอด แม้ไม่ได้ grab กันค้างหลังกด Ctrl+Esc
        if (ev[k].code == KEY_LEFTCTRL || ev[k].code == KEY_RIGHTCTRL)
            ctrlDown = (ev[k].value != 0);

        if (!devs[i].g) continue; // ไม่ได้ grab: ทิ้ง ไม่ส่งซ้ำกับทางปกติของระบบ

        // [เพิ่ม] กด/ปล่อย Caps ตอน grab: ดับไฟซ้ำ กันคีย์บอร์ดที่สลับไฟเอง
        if (ev[k].code == KEY_CAPSLOCK && ev[k].value != 2)
            set_caps_led(&devs[i], 0);

        handle_key(ev[k].code, ev[k].value);
    }
    return 1;
}

static void handle_inotify(int in) {
    char buf[512] __attribute__((aligned(alignof(struct inotify_event))));
    ssize_t n = read(in, buf, sizeof buf);
    if (n <= 0) return;
    int added = 0;
    for (char *p = buf; p < buf + n;) {
        struct inotify_event *e = (struct inotify_event *) p;
        if ((e->mask & IN_CREATE) && e->len > 0 && strncmp(e->name, "event", 5) == 0) {
            usleep(200000); // รอให้ระบบตั้งสิทธิ์ไฟล์ให้เสร็จก่อน
            char path[64];
            snprintf(path, sizeof path, "/dev/input/%s", e->name);
            int before = ndev;
            add_dev(path);
            if (ndev > before) added = 1;
        }
        p += sizeof(struct inotify_event) + e->len;
    }
    if (added && want) report_state();
}

int main(void) {
    int in = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (in >= 0) inotify_add_watch(in, "/dev/input", IN_CREATE);

    scan_devices();
    {
        char b[32];
        snprintf(b, sizeof b, "H %d\n", keyboard_count());
        say(b);
    }

    for (;;) {
        struct pollfd p[2 + MAX_DEV];
        int cnt = ndev;
        p[0].fd = 0;
        p[0].events = POLLIN;
        p[0].revents = 0;
        p[1].fd = in; // -1 ถ้า inotify ใช้ไม่ได้ (poll จะข้ามให้)
        p[1].events = POLLIN;
        p[1].revents = 0;
        for (int i = 0; i < cnt; i++) {
            p[2 + i].fd = devs[i].fd;
            p[2 + i].events = POLLIN;
            p[2 + i].revents = 0;
        }

        int r = poll(p, (nfds_t) (2 + cnt), -1);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }

        // อุปกรณ์: วนจากท้ายไปหัว เพราะ remove_dev สลับตัวท้ายมาแทนที่
        for (int i = cnt - 1; i >= 0; i--) {
            if (!p[2 + i].revents) continue;
            if (!read_dev(i)) remove_dev(i);
        }

        if (p[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            char c[64];
            ssize_t n = read(0, c, sizeof c);
            if (n <= 0) break; // InputServer หาย -> ออก (kernel ปล่อย grab ให้เอง)
            int quit = 0;
            for (ssize_t k = 0; k < n; k++) {
                if (c[k] == 'G') set_grab(1);
                else if (c[k] == 'U') set_grab(0);
                else if (c[k] == 'Q') quit = 1;
            }
            if (quit) break;
        }

        if (in >= 0 && (p[1].revents & POLLIN)) handle_inotify(in);
    }

    for (int i = 0; i < ndev; i++) {
        if (devs[i].g) {
            set_caps_led(&devs[i], 0); // ออกจากโปรแกรม: ดับไฟก่อนปล่อย
            ioctl(devs[i].fd, EVIOCGRAB, 0);
        }
        close(devs[i].fd);
    }
    return 0;
}