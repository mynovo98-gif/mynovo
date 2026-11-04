package com.mynovo.mynovo1;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

/**
 * ค้นหา service ADB ผ่าน mDNS
 *  - "_adb-tls-pairing._tcp"  : หน้า Pair device with pairing code
 *  - "_adb-tls-connect._tcp"  : พอร์ต Wireless debugging (เปลี่ยนทุกครั้งที่เปิดใหม่)
 * ส่ง callback กลับบน main thread
 */
public class MdnsDiscovery {

    public static final String TYPE_PAIRING = "_adb-tls-pairing._tcp";
    public static final String TYPE_CONNECT = "_adb-tls-connect._tcp";

    public interface Callback {
        void onFound(int port);
        void onLost();
        void onError(String message);
    }

    private final NsdManager nsd;
    private final String type;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager.DiscoveryListener listener;

    public MdnsDiscovery(Context ctx, String type, Callback callback) {
        this.nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.type = type;
        this.callback = callback;
    }

    public synchronized void start() {
        stop();
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                main.post(() -> callback.onError("mDNS start failed (" + t + ") code " + e));
            }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }

            @Override public void onServiceFound(NsdServiceInfo info) {
                resolve(info, 0);
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                main.post(callback::onLost);
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            listener = null;
            callback.onError("mDNS error: " + e);
        }
    }

    public synchronized void stop() {
        if (listener != null) {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) { }
            listener = null;
        }
    }

    // NsdManager resolve ทำได้ทีละตัว ถ้าชนกันให้ลองใหม่
    @SuppressWarnings("deprecation")
    private void resolve(NsdServiceInfo info, int attempt) {
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo si, int err) {
                    if (attempt < 10) {
                        main.postDelayed(() -> resolve(info, attempt + 1), 400);
                    } else {
                        main.post(() -> callback.onError("mDNS resolve failed, code " + err));
                    }
                }

                @Override public void onServiceResolved(NsdServiceInfo si) {
                    final int port = si.getPort();
                    main.post(() -> callback.onFound(port));
                }
            });
        } catch (Exception e) {
            if (attempt < 10) main.postDelayed(() -> resolve(info, attempt + 1), 400);
        }
    }
}
