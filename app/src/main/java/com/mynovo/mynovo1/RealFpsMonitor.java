package com.mynovo.mynovo1;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

public class RealFpsMonitor {

    public interface Listener {
        // source: "surface" = แม่นสุด (เฟรมจริงของแอปเป้าหมาย)
        //         "gfxinfo" = รองลงมา (ตัวนับสะสมของแอป ไม่ค่อยตรงกับเกม)
        //         "none"    = วัดไม่ได้ (fps = -1)
        void onFps(int fps, String source);
    }

    private static final String TAG = "RealFpsMonitor";
    private static final long WINDOW_NS = 1_000_000_000L;   // หน้าต่างคำนวณ 1 วิ
    private static final long STALE_NS = 1_500_000_000L;    // เฟรมล่าสุดเก่ากว่านี้ = เกมไม่ได้วาด
    private static final int MAX_CANDIDATES = 8;

    private final String pkg;
    private final Handler uiHandler;
    private final Listener listener;
    private java.lang.reflect.Method shizukuNewProcess = null;

    private volatile boolean running = false;
    private Thread thread;

    // --- สำหรับวิธี SurfaceFlinger latency ---
    private String cachedLayerName = null;
    private long lastLayerLookup = 0;

    // --- สำหรับ fallback gfxinfo ---
    private long lastFrameCount = -1;
    private long lastPollNanos = 0;

    // --- debug ---
    private String lastShellError = null;
    private String lastLogMsg = null;
    // [DEBUG] สาเหตุที่วัดจากเฟรมจริงไม่ได้ (โชว์บน overlay ชั่วคราว) แยกตามวิธี
    private volatile String why = "";      // ข้อความรวม = whyLat|whyTs
    private volatile String whyLat = "";   // สาเหตุของวิธี --latency
    private volatile String whyTs = "";    // สาเหตุของวิธี --timestats
    private int lastListLines = 0;

    // --- สำหรับวิธี SurfaceFlinger timestats (ใช้เมื่อ --latency ไม่มีข้อมูลเฟรม) ---
    private boolean tsEnabled = false;
    private java.util.Map<String, Long> tsPrev = new java.util.HashMap<>();
    private long tsPrevNanos = 0;
    private long latencyDisabledUntil = 0;   // --latency ใช้ไม่ได้ -> พักไว้ ไม่ต้องลองถี่ ๆ (กันเครื่องหน่วง)

    public RealFpsMonitor(String pkg, Handler uiHandler, Listener listener) {
        this.pkg = pkg;
        this.uiHandler = uiHandler;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        lastFrameCount = -1;
        lastPollNanos = 0;
        cachedLayerName = null;
        lastLayerLookup = 0;
        tsEnabled = false;
        tsPrev = new java.util.HashMap<>();
        tsPrevNanos = 0;
        latencyDisabledUntil = 0;
        whyLat = "";
        whyTs = "";
        why = "";
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
                // 1) วิธีแม่นสุด: SurfaceFlinger layer latency
                FpsResult r = trySurfaceFlingerLatency();
                if (r == null) r = tryTimeStats();
                why = whyLat + "|" + whyTs;
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
                                postFps(fps, "gfxinfo:" + why);
                            }
                        }
                        lastFrameCount = count;
                        lastPollNanos = now;
                    } else {
                        // 3) ทั้งสองวิธีใช้ไม่ได้
                        logOnce("none: pkg=" + pkg + " layer=" + cachedLayerName
                                + " shellError=" + lastShellError);
                        postFps(-1, "none:" + why);
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

    // เพดาน FPS ที่ให้แสดง (เช่น 60/90/120) ค่าที่วัดได้จริงต่ำกว่านี้ก็แสดงตามจริง
    private volatile int maxFps = 120;

    public void setMaxFps(int max) {
        maxFps = Math.max(1, Math.min(240, max));
    }

    private void postFps(int fps, String source) {
        if (fps > maxFps) fps = maxFps;
        final int shown = fps;
        if (uiHandler != null && listener != null) {
            uiHandler.post(() -> listener.onFps(shown, source));
        }
    }

    private void logOnce(String msg) {
        if (msg.equals(lastLogMsg)) return;
        lastLogMsg = msg;
        Log.w(TAG, msg);
    }

    private static class FpsResult { int fps; }

    // ===== วิธีที่ 1: SurfaceFlinger --latency (แม่นสุด) =====
    private FpsResult trySurfaceFlingerLatency() {
        try {
            if (pkg == null || pkg.isEmpty()) {
                whyLat = "pkg-empty";
                logOnce("pkg is empty");
                return null;
            }

            long uptime = SystemClock.uptimeMillis();
            if (uptime < latencyDisabledUntil) return null;
            if (cachedLayerName == null || uptime - lastLayerLookup > 5000) {
                String picked = pickBestLayer();
                lastLayerLookup = uptime;
                if (picked != null && !picked.equals(cachedLayerName)) {
                    Log.d(TAG, "using layer: " + picked);
                }
                cachedLayerName = picked;
            }
            if (cachedLayerName == null) return null;

            List<Long> ts = readLatency(cachedLayerName);
            if (ts == null || ts.isEmpty()) {
                // layer นี้ไม่มีข้อมูลเฟรม (หายไป/เป็น layer ผิดตัว) -> หาใหม่รอบหน้า
                whyLat = "empty-ts";
                cachedLayerName = null;
                lastLayerLookup = 0;
                return null;
            }

            whyLat = "ok";
            FpsResult r = computeFps(ts);
            if (r.fps == 0) {
                // เกมไม่ได้วาด หรือ layer นี้ไม่ใช่ตัวหลัก -> ให้หาใหม่รอบหน้าเผื่อเจอตัวที่ถูก
                lastLayerLookup = 0;
            }
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "surface method failed: " + t);
            return null;
        }
    }

    // คำนวณ fps จาก timestamp ของเฟรมที่ present จริงในช่วง 1 วิล่าสุด (ไม่ต้องจำสถานะรอบก่อน)
    private FpsResult computeFps(List<Long> ts) {
        Collections.sort(ts);
        long now = System.nanoTime();
        long last = ts.get(ts.size() - 1);
        FpsResult r = new FpsResult();

        long age = now - last;
        // ถ้านาฬิกาไม่ตรงกัน (age ติดลบ/ใหญ่ผิดปกติ) จะข้ามการเช็กเฟรมค้าง
        if (age > STALE_NS && age < 3_600_000_000_000L) {
            r.fps = 0;
            return r;
        }

        long windowStart = last - WINDOW_NS;
        int n = 0;
        long first = last;
        for (long t : ts) {
            if (t > windowStart) {
                if (n == 0) first = t;
                n++;
            }
        }
        if (n < 2) {
            r.fps = n;
            return r;
        }
        long span = last - first;
        if (span <= 0) {
            r.fps = n;
            return r;
        }
        int fps = (int) Math.round((n - 1) * 1_000_000_000.0 / span);
        r.fps = Math.min(fps, 240);
        return r;
    }

    // ดึง layer ทั้งหมดของแอป แล้วเลือกตัวที่มีเฟรมใหม่ถี่ที่สุด (= layer ที่เกมวาดจริง)
    private String pickBestLayer() {
        List<String> candidates = listCandidateLayers();
        if (candidates.isEmpty()) {
            if (lastShellError == null) whyLat = "no-layer:" + lastListLines;
            logOnce("no SurfaceFlinger layer contains pkg=" + pkg);
            return null;
        }

        long now = System.nanoTime();
        String best = null;
        int bestCount = -1;
        long bestLatest = -1;

        for (String c : candidates) {
            List<Long> ts = readLatency(c);
            if (ts == null || ts.isEmpty()) continue;
            long latest = Collections.max(ts);
            int count = 0;
            for (long t : ts) {
                if (t > latest - WINDOW_NS && now - latest < STALE_NS * 2) count++;
            }
            if (count > bestCount || (count == bestCount && latest > bestLatest)) {
                best = c;
                bestCount = count;
                bestLatest = latest;
            }
        }
        if (best == null) {
            whyLat = "no-data:" + candidates.size();
            latencyDisabledUntil = SystemClock.uptimeMillis() + 30000;
            logOnce("found " + candidates.size() + " layers but none has frame data, first="
                    + candidates.get(0));
            // [DEBUG] เก็บข้อมูลดิบของ layer แรกไว้ดูใน logcat
            String raw = runShell("dumpsys SurfaceFlinger --latency '"
                    + candidates.get(0).replace("'", "'\\''") + "' | head -5");
            Log.w(TAG, "latency raw=[" + raw + "] first=" + candidates.get(0));
        }
        return best;
    }

    private List<String> listCandidateLayers() {
        List<String> surfaceViews = new ArrayList<>();
        List<String> others = new ArrayList<>();
        String out = runShell("dumpsys SurfaceFlinger --list");
        if (out == null) {
            String e = String.valueOf(lastShellError);
            whyLat = "sh-fail:" + (e.length() > 30 ? e.substring(0, 30) : e);
            return surfaceViews;
        }
        lastListLines = out.split("\n").length;

        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || !t.contains(pkg)) continue;
            if (isIgnoredLayer(t)) continue;
            if (t.contains("SurfaceView")) surfaceViews.add(t);
            else others.add(t);
        }
        surfaceViews.addAll(others);
        if (surfaceViews.size() > MAX_CANDIDATES) {
            return new ArrayList<>(surfaceViews.subList(0, MAX_CANDIDATES));
        }
        return surfaceViews;
    }

    // อ่าน timestamp (actualPresentTime) ของเฟรมใน layer นั้น คืน null ถ้าสั่งคำสั่งไม่ได้
    private List<Long> readLatency(String layer) {
        String out = runShell("dumpsys SurfaceFlinger --latency '"
                + layer.replace("'", "'\\''") + "'");
        if (out == null) return null;

        String[] lines = out.split("\n");
        List<Long> res = new ArrayList<>();
        // บรรทัดแรกคือ refresh period ข้าม
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].trim().split("\\s+");
            if (parts.length < 2) continue;
            long t = parseValidTs(parts[1]);                 // actualPresentTime
            if (t < 0 && parts.length >= 3) t = parseValidTs(parts[2]); // frameReadyTime
            if (t > 0) res.add(t);
        }
        return res;
    }

    private static long parseValidTs(String s) {
        try {
            long v = Long.parseLong(s);
            if (v <= 0 || v == Long.MAX_VALUE) return -1;
            return v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===== วิธีที่ 1.5: SurfaceFlinger --timestats (นับเฟรมสะสมต่อ layer) =====
    // ใช้ได้บน Android รุ่นใหม่ที่ --latency ไม่คืนข้อมูล: อ่าน totalFrames ของ layer เกมซ้ำ ๆ แล้วหาส่วนต่าง/เวลา
    private FpsResult tryTimeStats() {
        try {
            if (pkg == null || pkg.isEmpty()) { whyTs = "pkg-empty"; return null; }

            if (!tsEnabled) {
                String en = runShell("dumpsys SurfaceFlinger --timestats -enable");
                if (en == null) { whyTs = "ts-sh"; return null; }
                runShell("dumpsys SurfaceFlinger --timestats -clear");   // ล้างสถิติเก่า ไม่ให้ layer เก่ากินโควตา
                tsEnabled = true;
            }

            String out = runShell("dumpsys SurfaceFlinger --timestats -dump -maxlayers 2000"
                    + " | grep -E 'layerName|totalFrames'");
            long now = System.nanoTime();
            if (out == null) { whyTs = "ts-sh"; return null; }

            java.util.Map<String, Long> cur = new java.util.HashMap<>();
            int all = 0;
            String name = null;
            for (String line : out.split("\n")) {
                String t = line.trim();
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                if (t.startsWith("layerName")) {
                    name = t.substring(eq + 1).trim();
                    all++;
                } else if (t.startsWith("totalFrames") && name != null) {
                    try {
                        long v = Long.parseLong(t.substring(eq + 1).trim());
                        if (name.contains(pkg) && !isIgnoredLayer(name)) cur.put(name, v);
                    } catch (NumberFormatException ignored) {}
                    name = null;
                }
            }

            // [DEBUG] ดูใน logcat ว่า timestats เห็น layer ของเกมไหม
            Log.d(TAG, "timestats layers=" + all + " matched=" + cur);

            FpsResult r = null;
            if (cur.isEmpty()) {
                whyTs = (all == 0) ? "ts-empty" : "ts-nolayer:" + all;
            } else if (tsPrevNanos == 0) {
                whyTs = "ts-base";
            } else {
                double sec = (now - tsPrevNanos) / 1_000_000_000.0;
                long bestDelta = -1;
                for (java.util.Map.Entry<String, Long> e : cur.entrySet()) {
                    Long p = tsPrev.get(e.getKey());
                    if (p == null) continue;
                    long d = e.getValue() - p;
                    if (d < 0) continue;          // ตัวนับถูกรีเซ็ต
                    if (d > bestDelta) bestDelta = d;
                }
                if (bestDelta >= 0 && sec > 0.2) {
                    r = new FpsResult();
                    r.fps = (int) Math.min(240, Math.round(bestDelta / sec));
                    whyTs = "ok";
                } else {
                    whyTs = "ts-base";
                }
            }
            tsPrev = cur;
            tsPrevNanos = now;
            return r;
        } catch (Throwable t) {
            whyTs = "ts-err";
            Log.w(TAG, "timestats failed: " + t);
            return null;
        }
    }

    private static boolean isIgnoredLayer(String t) {
        return t.startsWith("Bounds for") || t.startsWith("Background for")
                || t.contains("InputMethod") || t.contains("NavigationBar")
                || t.contains("StatusBar") || t.contains("Toast")
                || t.startsWith("Task=");
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
            if (!Shizuku.pingBinder()) {
                lastShellError = "shizuku not running";
                return null;
            }
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
            lastShellError = null;
            return sb.toString();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            lastShellError = c.toString();
            return null;
        }
    }
}
