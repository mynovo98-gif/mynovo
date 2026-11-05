package com.mynovo.mynovo1;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class MdnsDiscovery {

    private static final String TAG = "MdnsDiscovery";

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
        Log.d(TAG, "created for type=" + type + " nsd=" + nsd);
    }

    public synchronized void start() {
        Log.d(TAG, "start() type=" + type);
        stop();
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                Log.e(TAG, "onStartDiscoveryFailed type=" + t + " code=" + e);
                main.post(() -> callback.onError("mDNS start failed (" + t + ") code " + e));
            }
            @Override public void onStopDiscoveryFailed(String t, int e) {
                Log.e(TAG, "onStopDiscoveryFailed type=" + t + " code=" + e);
            }
            @Override public void onDiscoveryStarted(String t) {
                Log.d(TAG, "onDiscoveryStarted type=" + t);
            }
            @Override public void onDiscoveryStopped(String t) {
                Log.d(TAG, "onDiscoveryStopped type=" + t);
            }

            @Override public void onServiceFound(NsdServiceInfo info) {
                Log.d(TAG, "onServiceFound: " + info);
                resolve(info, 0);
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                Log.w(TAG, "onServiceLost: " + info);
                main.post(callback::onLost);
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            Log.d(TAG, "discoverServices() invoked for type=" + type);
        } catch (Exception e) {
            Log.e(TAG, "discoverServices() threw", e);
            listener = null;
            callback.onError("mDNS error: " + e);
        }
    }

    public synchronized void stop() {
        Log.d(TAG, "stop() type=" + type);
        if (listener != null) {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) { }
            listener = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void resolve(NsdServiceInfo info, int attempt) {
        Log.d(TAG, "resolve() attempt=" + attempt + " info=" + info);
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo si, int err) {
                    Log.e(TAG, "onResolveFailed code=" + err + " attempt=" + attempt);
                    if (attempt < 10) {
                        main.postDelayed(() -> resolve(info, attempt + 1), 400);
                    } else {
                        main.post(() -> callback.onError("mDNS resolve failed, code " + err));
                    }
                }

                @Override public void onServiceResolved(NsdServiceInfo si) {
                    final int port = si.getPort();
                    Log.d(TAG, "onServiceResolved port=" + port + " info=" + si);
                    main.post(() -> callback.onFound(port));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "resolveService() threw", e);
            if (attempt < 10) main.postDelayed(() -> resolve(info, attempt + 1), 400);
        }
    }
}