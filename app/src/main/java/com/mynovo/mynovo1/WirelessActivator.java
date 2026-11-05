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
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbStream;

public class WirelessActivator {

    private static final String TAG = "WirelessActivator";

    // จำนวนครั้งและระยะห่างในการลองต่อ shell server หลังสั่งรัน activate.sh
    private static final int MAX_CONNECT_ATTEMPTS = 15;
    private static final long CONNECT_RETRY_MS = 1000L;

    /**
     * สร้างคำสั่งที่จะรันผ่าน adb shell โดยใช้ path จริงจาก ShellServerSetup
     * (สร้าง activate.sh + token ให้ด้วย) ต้องเรียกนอก main thread
     */
    public static String buildActivateCmd(Context c) throws java.io.IOException {
        String run = ShellServerSetup.prepare(c);       // "sh /sdcard/.../files/exe/activate.sh"
        String script = run.substring(3);               // ตัด "sh " ออก
        return "if [ -f " + script + " ]; then " + run
                + "; else echo NO_FILE:" + script + "; fi";
    }

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
    private volatile String lastOutput = "";

    public WirelessActivator(Context ctx) {
        this.app = ctx.getApplicationContext();
        // ShellServerClient ต้องมี context ไม่งั้น connectBlocking() คืน false เงียบ ๆ
        ShellServerClient.setContext(this.app);
    }

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

    public void start() {
        Log.d(TAG, "start() called");
        if (Build.VERSION.SDK_INT < 30) {
            message = "Wireless debugging requires Android 11+";
            Log.w(TAG, "start() aborted: SDK < 30");
            return;
        }
        registerReceiverIfNeeded();
        createChannel();

        connectDiscovery = new MdnsDiscovery(app, MdnsDiscovery.TYPE_CONNECT,
                new MdnsDiscovery.Callback() {
                    @Override public void onFound(int port) {
                        Log.d(TAG, "connectDiscovery.onFound: port=" + port);
                        connectPort = port;
                        tryConnect();
                    }
                    @Override public void onLost() {
                        Log.w(TAG, "connectDiscovery.onLost");
                        connectPort = -1;
                        connected = false;
                    }
                    @Override public void onError(String msg) {
                        Log.e(TAG, "connectDiscovery.onError: " + msg);
                        message = msg;
                    }
                });
        connectDiscovery.start();
        Log.d(TAG, "connectDiscovery.start() invoked");
    }

    public void stop() {
        Log.d(TAG, "stop() called");
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

    public void startPairing() {
        Log.d(TAG, "startPairing() called");
        if (Build.VERSION.SDK_INT < 30) return;
        message = "Searching pairing port... open \"Pair with pairing code\" in Wireless debugging";
        if (pairingDiscovery != null) pairingDiscovery.stop();
        pairingDiscovery = new MdnsDiscovery(app, MdnsDiscovery.TYPE_PAIRING,
                new MdnsDiscovery.Callback() {
                    @Override public void onFound(int port) {
                        Log.d(TAG, "pairingDiscovery.onFound: port=" + port);
                        pairPort = port;
                        message = "Pairing port found: " + port + " — enter the code in the notification";
                        showCodeNotification("Enter the 6-digit pairing code");
                    }
                    @Override public void onLost() {
                        Log.w(TAG, "pairingDiscovery.onLost");
                        pairPort = -1;
                        cancelNotification();
                    }
                    @Override public void onError(String msg) {
                        Log.e(TAG, "pairingDiscovery.onError: " + msg);
                        message = msg;
                    }
                });
        pairingDiscovery.start();
    }

    private void submitCode(String code) {
        Log.d(TAG, "submitCode() called, pairPort=" + pairPort);
        final int port = pairPort;
        if (port < 0 || code.isEmpty()) {
            message = "Pairing screen closed — open it again";
            showCodeNotification("Pairing screen closed — open it again");
            Log.w(TAG, "submitCode aborted: port<0 or empty code");
            return;
        }
        message = "Pairing...";
        notifyText("Pairing...");
        io.execute(() -> {
            try {
                boolean ok = AdbManager.get(app).pair(HOST, port, code);
                Log.d(TAG, "AdbManager.pair() result=" + ok);
                if (ok) {
                    paired = true;
                    message = "Paired — connecting...";
                    notifyText("Paired successfully");
                    bringAppToFront();
                    tryConnect();
                } else {
                    message = "Pair failed (wrong code or pairing screen closed)";
                    showCodeNotification("Pair failed — check code, try again");
                }
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null) r = r.getCause();
                Log.e(TAG, "submitCode pair failed", t);
                message = "Pair failed: " + t + " | " + r;
                showCodeNotification("Error: " + r);
            }
        });
    }

    private void bringAppToFront() {
        main.post(() -> {
            try {
                Intent i = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    app.startActivity(i);
                }
            } catch (Throwable ignored) { }
        });
    }

    private void tryConnect() {
        Log.d(TAG, "tryConnect: connectPort=" + connectPort + " connected=" + connected + " connecting=" + connecting);
        final int port = connectPort;
        if (port < 0 || connected || connecting) {
            Log.w(TAG, "tryConnect aborted");
            return;
        }
        connecting = true;
        io.execute(() -> {
            try {
                boolean ok = AdbManager.get(app).connect(HOST, port);
                Log.d(TAG, "AdbManager.connect() result=" + ok);
                if (ok) {
                    connected = true;
                    paired = true;
                    message = "";
                    cancelNotification();
                }
            } catch (Throwable t) {
                Log.e(TAG, "tryConnect failed", t);
                if (paired) message = "Connect failed: " + t;
            } finally {
                connecting = false;
            }
        });
    }

    public void activate() {
        Log.d(TAG, "activate() called: connected=" + connected + " paired=" + paired);
        if (!connected) {
            message = "Not connected yet";
            Log.w(TAG, "activate() aborted: not connected");
            return;
        }
        message = "Activating...";
        lastOutput = "";
        io.execute(() -> {
            try {
                // สร้าง activate.sh + token ใหม่ทุกครั้ง แล้วใช้ path จริง (files/exe/)
                final String cmd = buildActivateCmd(app);
                Log.d(TAG, "activate: opening stream with cmd=" + cmd);
                final AdbStream s = AdbManager.get(app).openStream("shell:" + cmd);
                new Thread(() -> {
                    try (InputStream in = s.openInputStream()) {
                        byte[] buf = new byte[1024];
                        int n;
                        StringBuilder sb = new StringBuilder();
                        while ((n = in.read(buf)) != -1) {
                            if (sb.length() < 600) {
                                sb.append(new String(buf, 0, n));
                                lastOutput = sb.toString().trim();
                            }
                        }
                        Log.d(TAG, "activate: shell output=" + lastOutput);
                    } catch (Throwable t) {
                        Log.e(TAG, "activate: read output failed", t);
                    } finally {
                        try { s.close(); } catch (Throwable ignored) { }
                    }
                }).start();
                main.post(() -> connectShellServer(0));
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null) r = r.getCause();
                Log.e(TAG, "activate() failed", t);
                message = "Activate failed: " + t + " | " + r;
            }
        });
    }

    private void connectShellServer(int attempt) {
        Log.d(TAG, "connectShellServer attempt=" + attempt);
        ShellServerClient.connectAsync(ok -> {
            Log.d(TAG, "ShellServerClient.connectAsync result=" + ok);
            if (ok) {
                activated = true;
                message = "";
            } else if (attempt < MAX_CONNECT_ATTEMPTS) {
                main.postDelayed(() -> connectShellServer(attempt + 1), CONNECT_RETRY_MS);
            } else {
                message = "Server did not start. Output: "
                        + (lastOutput.isEmpty() ? "(none)" : lastOutput);
                Log.e(TAG, "connectShellServer: gave up, lastOutput=" + lastOutput);
            }
        });
    }

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
