package com.mynovo.mynovo1;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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

public class WirelessActivator {

    public static final String ACTIVATE_CMD =
            "sh /sdcard/Android/data/com.mynovo.mynovo1/files/activate.sh";

    private static final String HOST = "127.0.0.1";
    private static final String CHANNEL_ID = "adb_pairing";
    private static final int NOTIF_ID = 4201;
    private static final String ACTION_CODE = "com.mynovo.mynovo1.PAIRING_CODE";
    private static final String KEY_CODE = "pairing_code";

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

    // ---------- ขอสิทธิ์แจ้งเตือน (Android 13+) ----------

    public static void requestNotificationPermission(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private static String rootCause(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return t + (r != t ? " | cause: " + r : "");
    }

    // ---------- เช็กการตั้งค่าระบบ ----------

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

    // ---------- lifecycle ----------

    public void start() {
        if (Build.VERSION.SDK_INT < 30) {
            message = "Wireless debugging requires Android 11+";
            return;
        }
        registerReceiverIfNeeded();
        createChannel();

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

    // ---------- ขั้น 4: Pair ----------

    public void startPairing() {
        if (Build.VERSION.SDK_INT < 30) return;
        registerReceiverIfNeeded();
        createChannel();
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
        notifyText("Pairing...");
        io.execute(() -> {
            try {
                boolean ok = AdbManager.get(app).pair(HOST, port, code);
                if (ok) {
                    paired = true;
                    message = "Paired — connecting...";
                    notifyText("Paired successfully");
                    tryConnect();
                } else {
                    message = "Pair failed (wrong code or port expired)";
                    showCodeNotification("Pair failed — check code, try again");
                }
            } catch (Throwable t) {
                message = "Pair failed: " + rootCause(t);
                showCodeNotification("Error: " + rootCause(t));
            }
        });
    }

    // ---------- ขั้น 5: Connect ----------

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
                if (paired) message = "Connect failed: " + rootCause(t);
            } finally {
                connecting = false;
            }
        });
    }

    // ---------- ขั้น 6: Activate ----------

    public void activate() {
        if (!connected) return;
        message = "";
        io.execute(() -> {
            try {
                final AdbStream s = AdbManager.get(app).openStream("shell:" + ACTIVATE_CMD);
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
                message = "Activate failed: " + rootCause(t);
            }
        });
    }

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

    // ---------- notification ----------

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
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
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