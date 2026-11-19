package com.mynovo.mynovo1;

import android.os.Handler;
import rikka.shizuku.Shizuku;

public class RealFpsMonitor {

    public interface Listener {
        // source: "surface" = แม่นสุด (เฟรมจริงของแอปเป้าหมาย)
        //         "gfxinfo" = รองลงมา (ตัวนับสะสมของแอป)
        //         "vsync"   = ประมาณการจากจอ (ใช้เมื่อสองวิธีบนทำไม่ได้)
        void onFps(int fps, String source);
    }

    private final String pkg;
    private final Handler uiHandler;
    private final Listener listener;
    private java.lang.reflect.Method shizukuNewProcess = null;

    private volatile boolean running = false;
    private Thread thread;

    // --- สำหรับวิธี SurfaceFlinger latency ---
    private String cachedLayerName = null;
    private long lastLayerLookup = 0;
    private long lastSeenPresentNanos = -1;

    // --- สำหรับ fallback gfxinfo ---
    private long lastFrameCount = -1;
    private long lastPollNanos = 0;

    public RealFpsMonitor(String pkg, Handler uiHandler, Listener listener) {
        this.pkg = pkg;
        this.uiHandler = uiHandler;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrameCount = -1;
        lastSeenPresentNanos = -1;
        cachedLayerName = null;
        thread = new Thread(this::loop, "RealFpsMonitor");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            long sleepMs = 700;
            try {
                // 1) ลองวิธีแม่นสุดก่อน: SurfaceFlinger layer latency
                FpsResult r = trySurfaceFlingerLatency();
                if (r != null) {
                    postFps(r.fps, "surface");
                } else {
                    // 2) fallback: ตัวนับสะสมจาก gfxinfo
                    long count = queryGfxFrameCount();
                    long now = System.nanoTime();
                    if (count >= 0) {
                        if (lastFrameCount >= 0 && count >= lastFrameCount && lastPollNanos != 0) {
                            double elapsedSec = (now - lastPollNanos) / 1_000_000_000.0;
                            if (elapsedSec > 0.2) {
                                int fps = (int) Math.round((count - lastFrameCount) / elapsedSec);
                                postFps(fps, "gfxinfo");
                            }
                        }
                        lastFrameCount = count;
                        lastPollNanos = now;
                    } else {
                        // 3) ทั้งสองวิธีใช้ไม่ได้ -> แจ้งว่าไม่สามารถวัดได้
                        postFps(-1, "none");
                    }
                }
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private void postFps(int fps, String source) {
        if (uiHandler != null && listener != null) {
            uiHandler.post(() -> listener.onFps(fps, source));
        }
    }

    private static class FpsResult { int fps; }

    // ===== วิธีที่ 1: SurfaceFlinger --latency (แม่นสุด) =====
    private FpsResult trySurfaceFlingerLatency() {
        try {
            String layer = resolveLayerName();
            if (layer == null) return null;

            String out = runShell("dumpsys SurfaceFlinger --latency \"" + layer + "\"");
            if (out == null || out.trim().isEmpty()) {
                cachedLayerName = null; // layer อาจหายไปแล้ว (เกมสลับหน้าจอ/ปิด) ให้หาใหม่รอบหน้า
                return null;
            }

            String[] lines = out.split("\n");
            if (lines.length < 2) return null;

            java.util.List<Long> newTimestamps = new java.util.ArrayList<>();
            long maxSeenThisPoll = lastSeenPresentNanos;

            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].trim().split("\\s+");
                if (parts.length < 2) continue;
                long actualPresentNanos;
                try {
                    actualPresentNanos = Long.parseLong(parts[1]);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                // ค่า sentinel ของเฟรมที่ยังไม่ถูก present จริง
                if (actualPresentNanos <= 0 || actualPresentNanos == Long.MAX_VALUE) continue;

                if (actualPresentNanos > lastSeenPresentNanos) {
                    newTimestamps.add(actualPresentNanos);
                    if (actualPresentNanos > maxSeenThisPoll) maxSeenThisPoll = actualPresentNanos;
                }
            }

            if (lastSeenPresentNanos < 0) {
                // รอบแรก แค่บันทึก baseline ยังคำนวณ fps ไม่ได้
                lastSeenPresentNanos = maxSeenThisPoll;
                return null;
            }

            if (newTimestamps.isEmpty()) {
                // ไม่มีเฟรมใหม่เลยในช่วงที่ผ่านมา = เกมค้าง/ไม่ได้วาดอะไรเลย
                FpsResult r = new FpsResult();
                r.fps = 0;
                return r;
            }

            java.util.Collections.sort(newTimestamps);
            long span = newTimestamps.get(newTimestamps.size() - 1) - lastSeenPresentNanos;
            if (span <= 0) return null;

            double elapsedSec = span / 1_000_000_000.0;
            int fps = (int) Math.round(newTimestamps.size() / elapsedSec);

            lastSeenPresentNanos = maxSeenThisPoll;

            FpsResult r = new FpsResult();
            r.fps = fps;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    // หาชื่อ layer ที่ตรงกับแอปเป้าหมาย (cache ไว้ ค้นใหม่ทุก 5 วิ เผื่อเกมสลับหน้าจอ/Activity)
    private String resolveLayerName() {
        long now = android.os.SystemClock.uptimeMillis();
        if (cachedLayerName != null && now - lastLayerLookup < 5000) return cachedLayerName;
        lastLayerLookup = now;

        String out = runShell("dumpsys SurfaceFlinger --list");
        if (out == null) return null;

        String best = null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.contains(pkg) && !t.contains("InputMethod") && !t.contains("NavigationBar")
                    && !t.contains("StatusBar") && !t.contains("Toast")) {
                // ชอบบรรทัดที่มีรูปแบบ "package/activity#index" (surface เนื้อหาจริง)
                if (t.contains("/") || best == null) best = t;
                if (t.contains("/")) break;
            }
        }
        cachedLayerName = best;
        return best;
    }

    // ===== วิธีที่ 2: gfxinfo total frames (fallback) =====
    private long queryGfxFrameCount() {
        String out = runShell("dumpsys gfxinfo " + pkg);
        if (out == null) return -1;
        java.util.regex.Pattern pat =
                java.util.regex.Pattern.compile("Total frames rendered:\\s*(\\d+)");
        for (String line : out.split("\n")) {
            java.util.regex.Matcher m = pat.matcher(line);
            if (m.find()) return Long.parseLong(m.group(1));
        }
        return -1;
    }

    private String runShell(String cmd) {
        try {
            if (!Shizuku.pingBinder()) return null;
            if (shizukuNewProcess == null) {
                shizukuNewProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                shizukuNewProcess.setAccessible(true);
            }
            Process p = (Process) shizukuNewProcess.invoke(null, new Object[]{
                    new String[]{"sh", "-c", cmd + " 2>/dev/null"}, null, null});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            try { r.close(); } catch (Exception ignored) {}
            try { p.waitFor(); } catch (Exception ignored) {}
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}