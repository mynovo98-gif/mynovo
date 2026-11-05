package com.mynovo.mynovo1;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbStream;

/**
 * ตรรกะทั้งหมดของ Wireless activation (ไม่มี UI)
 *
 * ลำดับ:
 *  1-3) เช็ก Developer options / USB debugging / Wireless debugging
 *  4)   startPairing()  -> หา pairing port ผ่าน mDNS -> แจ้งเตือนให้พิมพ์โค้ด 6 หลัก -> adb.pair()
 *  5)   หา connect port ผ่าน mDNS อัตโนมัติ -> adb.connect()
 *  6)   activate()      -> รันคำสั่งเปิด InputServer ผ่าน adb shell
 */
public class WirelessActivator {

    /** คำสั่งที่รันตอนกด Activate — แก้ให้ตรงกับ activate.sh ของคุณ */
    public static final String ACTIVATE_CMD =
            "sh /sdcard/Android/data/com.mynovo.mynovo1/files/activate.sh";

    private static final String HOST = "127.0.0.1";
    private static final String CHANNEL_ID = "adb_pairing";
    private static final int NOTIF_ID = 4201;
    private static final String ACTION_CODE = "com.mynovo.mynovo1.PAIRING_CODE";
    private static final String KEY_CODE = "pairing_code";

    // ---- state ที่ UI อ่านได้ ----
    public volatile boolean paired;
    public volatile boolean connected;
    public volatile boolean activated;
    public volatile String message = "";

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MdnsDiscovery pairingDiscovery;
    private MdnsDiscovery connectDiscovery;
    private volatile int pairPort = -1;
    private volatile int connectPort = -1;
    private volatile boolean connecting;
    private boolean receiverRegistered;

    public WirelessActivator(Context ctx) {
        this.app = ctx.getApplicationContext();
    }

    // ---------------- เช็กการตั้งค่าระบบ ----------------

    public static boolean devOptionsOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    public static boolean usbDebuggingOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) == 1;
    }

    public static boolean wirelessDebuggingOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    // ---------------- lifecycle ----------------

    public void start() {
        if (Build.VERSION.SDK_INT < 30) {
            message = "Wireless debugging requires Android 11+";
            return;
        }
        registerReceiverIfNeeded();
        createChannel();

        // ถ้า pair ไว้แล้ว แค่หาพอร์ต connect ก็เชื่อมต่อได้เลย
        connectDiscovery = new MdnsDiscovery(app, MdnsDiscovery.TYPE_CONNECT,
                new MdnsDiscovery.Callback() {
                    @Override public void onFound(int port) {
                        connectPort = port;
                        tryConnect();
                    }
                    @Override public void onLost() {
                        connectPort = -1;
                        connected = false;
                    }
                    @Override public void onError(String msg) {
                        message = msg;
                    }
                });
        connectDiscovery.start();
    }

    public void stop() {
        if (connectDiscovery != null) connectDiscovery.stop();
        if (pairingDiscovery != null) pairingDiscovery.stop();
        connectDiscovery = null;
        pairingDiscovery = null;
        cancelNotification();
        if (receiverRegistered) {
            try { app.unregisterReceiver(receiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
    }

    // ---------------- ขั้น 4: Pair ----------------

    /** เรียกตอนกดปุ่ม PAIR (หลังจากนั้น UI พาผู้ใช้ไปหน้า Developer options) */
    public void startPairing() {
        if (Build.VERSION.SDK_INT < 30) return;
        message = "Searching pairing port... open \"Pair with pairing code\" in Wireless debugging";
        if (pairingDiscovery != null) pairingDiscovery.stop();
        pairingDiscovery = new MdnsDiscovery(app, MdnsDiscovery.TYPE_PAIRING,
                new MdnsDiscovery.Callback() {
                    @Override public void onFound(int port) {
                        pairPort = port;
                        message = "Pairing port found: " + port + " — enter the code in the notification";
                        showCodeNotification("Enter the 6-digit pairing code");
                    }
                    @Override public void onLost() {
                        pairPort = -1;
                        cancelNotification();
                    }
                    @Override public void onError(String msg) {
                        message = msg;
                    }
                });
        pairingDiscovery.start();
    }

    private void submitCode(String code) {
        final int port = pairPort;
        if (port < 0 || code.isEmpty()) {
            message = "Pairing screen closed — open it again";
            showCodeNotification("Pairing screen closed — open it again");
            return;
        }
        message = "Pairing...";
        io.execute(() -> {
            try {
                AdbManager.get(app).pair(HOST, port, code);
                paired = true;
                message = "Paired — connecting...";
                notifyText("Paired successfully");
                tryConnect();
            } catch (Throwable t) {
                message = "Pair failed: " + t;
                showCodeNotification("Pair failed, try again");
            }
        });
    }

    // ---------------- ขั้น 5: Connect ----------------

    private void tryConnect() {
        final int port = connectPort;
        if (port < 0 || connected || connecting) return;
        connecting = true;
        io.execute(() -> {
            try {
                if (AdbManager.get(app).connect(HOST, port)) {
                    connected = true;
                    paired = true;
                    message = "";
                    cancelNotification();
                }
            } catch (Throwable t) {
                // ยังไม่ได้ pair จะ connect ไม่ผ่าน — ปกติ แสดงข้อความเฉพาะตอน pair แล้ว
                if (paired) message = "Connect failed: " + t;
            } finally {
                connecting = false;
            }
        });
    }

    // ---------------- ขั้น 6: Activate ----------------

    public void activate() {
        if (!connected) return;
        message = "";
        io.execute(() -> {
            try {
                final AdbStream s = AdbManager.get(app).openStream("shell:" + ACTIVATE_CMD);
                // อ่าน output ทิ้งในเธรดแยก — ถ้าคำสั่งไม่ detach stream จะค้างไว้ ซึ่งช่วยให้ server ไม่ถูกฆ่า
                new Thread(() -> {
                    try (InputStream in = s.openInputStream()) {
                        byte[] buf = new byte[1024];
                        while (in.read(buf) != -1) { /* drain */ }
                    } catch (Throwable ignored) {
                    } finally {
                        try { s.close(); } catch (Throwable ignored) { }
                    }
                }).start();
                main.post(() -> connectShellServer(0));
            } catch (Throwable t) {
                message = "Activate failed: " + t;
            }
        });
    }

    /** รอให้ InputServer ขึ้น แล้วให้ ShellServerClient (ของเดิมในแอป) เชื่อมต่อ ลองซ้ำสูงสุด 10 ครั้ง */
    private void connectShellServer(int attempt) {
        ShellServerClient.connectAsync(ok -> {
            if (ok) {
                activated = true;
                message = "";
            } else if (attempt < 10) {
                main.postDelayed(() -> connectShellServer(attempt + 1), 700);
            } else {
                message = "Server did not start — check ACTIVATE_CMD";
            }
        });
    }

    // ---------------- notification (พิมพ์โค้ดโดยไม่ต้องออกจากหน้า Settings) ----------------

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Bundle r = RemoteInput.getResultsFromIntent(i);
            if (r == null) return;
            CharSequence code = r.getCharSequence(KEY_CODE);
            if (code != null) submitCode(code.toString().trim());
        }
    };

    private void registerReceiverIfNeeded() {
        if (receiverRegistered) return;
        ContextCompat.registerReceiver(app, receiver, new IntentFilter(ACTION_CODE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ADB pairing",
                    NotificationManager.IMPORTANCE_HIGH);
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private void showCodeNotification(String text) {
        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel("Pairing code").build();
        Intent intent = new Intent(ACTION_CODE).setPackage(app.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Enter code", pi)
                .addRemoteInput(input).build();

        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Wireless debugging pairing")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .addAction(action);
        notifySafe(b);
    }

    private void notifyText(String text) {
        notifySafe(new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Wireless debugging")
                .setContentText(text)
                .setTimeoutAfter(4000));
    }

    private void notifySafe(NotificationCompat.Builder b) {
        try {
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) { }
    }

    private void cancelNotification() {
        ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(NOTIF_ID);
    }
}
