package com.mynovo.mynovo1;

import android.util.Log;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

public class VirtualInputDriver {
    private static final String TAG = "VirtualInputDriver";
    private static boolean isInitialized = false;

    static {
        try {
            System.loadLibrary("native-lib");
            isInitialized = true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load native-lib", e);
            isInitialized = false;
        }
    }

    public static native boolean nativeInitTouch();
    public static native boolean nativeInitMouse();
    public static native void nativeInjectTouch(int slot, int trackingId, int x, int y, boolean down);
    public static native void nativeInjectMouseMove(int dx, int dy);

    public static boolean initializeVirtualDevice() {
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method newProcessMethod = shizukuClass.getMethod("newProcess", String[].class, String[].class, String.class);
            newProcessMethod.setAccessible(true);
            
            Process process = (Process) newProcessMethod.invoke(null, new String[]{"sh"}, null, null);
            if (process != null) {
                FileOutputStream stdin = (FileOutputStream) process.getOutputStream();
                String setupCommands = "chmod 666 /dev/uinput\n";
                stdin.write(setupCommands.getBytes());
                stdin.flush();
                stdin.close();
                process.waitFor();
            }
            
            boolean touchOk = nativeInitTouch();
            boolean mouseOk = nativeInitMouse();
            
            Log.i(TAG, "Virtual input subsystem ready: Touch=" + touchOk + ", Mouse=" + mouseOk);
            return touchOk && mouseOk;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize virtual input device", e);
            return false;
        }
    }
}