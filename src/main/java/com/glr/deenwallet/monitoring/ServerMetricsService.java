package com.glr.deenwallet.monitoring;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the machine (CPU, memory, disk, network, load) and this application (Java memory, threads, requests).
 * A small sampler runs every 5 seconds so the Metrics tab can draw the last 10 minutes as live charts.
 */
@Service
public class ServerMetricsService {

    private static final int KEEP = 120;

    private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
    private long prevTotal = -1, prevIdle = -1;
    private long prevRx = -1, prevTx = -1, prevNetAt = 0;
    private volatile double cpuPercent = 0;
    private volatile double rxRate = 0, txRate = 0;

    // ---- request statistics (last 10 minutes, one bucket per minute) ----
    private static final class Bucket {
        long minute, count, c4, c5, totalMs, maxMs;
        String slowPath = "";
    }
    private final Bucket[] buckets = new Bucket[10];

    public synchronized void recordRequest(String path, long ms, int status) {
        long m = System.currentTimeMillis() / 60000L;
        int i = (int) (m % 10);
        Bucket b = buckets[i];
        if (b == null || b.minute != m) { b = new Bucket(); b.minute = m; buckets[i] = b; }
        b.count++;
        b.totalMs += ms;
        if (status >= 500) b.c5++; else if (status >= 400) b.c4++;
        if (ms > b.maxMs) { b.maxMs = ms; b.slowPath = path.length() > 80 ? path.substring(0, 80) : path; }
    }

    private synchronized Map<String, Object> requestStats() {
        long now = System.currentTimeMillis() / 60000L;
        long count = 0, c4 = 0, c5 = 0, totalMs = 0, maxMs = 0, last = 0;
        String slow = "";
        for (Bucket b : buckets) {
            if (b == null || now - b.minute >= 5) continue;
            count += b.count; c4 += b.c4; c5 += b.c5; totalMs += b.totalMs;
            if (b.maxMs > maxMs) { maxMs = b.maxMs; slow = b.slowPath; }
            if (b.minute == now) last = b.count;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastMinute", last);
        m.put("last5Min", count);
        m.put("clientErrors5Min", c4);
        m.put("serverErrors5Min", c5);
        m.put("avgMs", count == 0 ? 0 : Math.round((double) totalMs / count));
        m.put("slowestMs", maxMs);
        m.put("slowestPath", slow);
        return m;
    }

    // ---- 5-second sampler ----
    @Scheduled(fixedRate = 5000, initialDelay = 2000)
    public void sample() {
        try {
            readCpu();
            Map<String, Long> mem = readMem();
            readNet();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", System.currentTimeMillis());
            p.put("cpu", round1(cpuPercent));
            long total = mem.getOrDefault("MemTotal", 0L), avail = mem.getOrDefault("MemAvailable", 0L);
            p.put("ram", total > 0 ? round1(100.0 * (total - avail) / total) : 0.0);
            p.put("rx", Math.round(rxRate));
            p.put("tx", Math.round(txRate));
            synchronized (history) {
                history.addLast(p);
                while (history.size() > KEEP) history.removeFirst();
            }
        } catch (RuntimeException ignored) {
            // sampling is best effort
        }
    }

    private void readCpu() {
        List<String> lines = readLines("/proc/stat", 1);
        if (!lines.isEmpty() && lines.get(0).startsWith("cpu ")) {
            String[] f = lines.get(0).trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < Math.min(f.length, 9); i++) total += parse(f[i]);
            long idle = parse(f[4]) + (f.length > 5 ? parse(f[5]) : 0);
            if (prevTotal >= 0 && total > prevTotal) {
                cpuPercent = Math.max(0, Math.min(100, 100.0 * ((total - prevTotal) - (idle - prevIdle)) / (total - prevTotal)));
            }
            prevTotal = total; prevIdle = idle;
            return;
        }
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean x) {
                double v = x.getCpuLoad();
                if (v >= 0) cpuPercent = v * 100.0;
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private Map<String, Long> readMem() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String line : readLines("/proc/meminfo", 60)) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String[] v = line.substring(c + 1).trim().split("\\s+");
            m.put(line.substring(0, c), parse(v[0]) * 1024L);
        }
        if (m.isEmpty()) {
            try {
                java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                if (os instanceof com.sun.management.OperatingSystemMXBean x) {
                    m.put("MemTotal", x.getTotalMemorySize());
                    m.put("MemAvailable", x.getFreeMemorySize());
                    m.put("SwapTotal", x.getTotalSwapSpaceSize());
                    m.put("SwapFree", x.getFreeSwapSpaceSize());
                }
            } catch (RuntimeException | LinkageError ignored) { }
        }
        return m;
    }

    private void readNet() {
        long rx = 0, tx = 0;
        boolean any = false;
        for (String line : readLines("/proc/net/dev", 40)) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String name = line.substring(0, c).trim();
            if (name.equals("lo")) continue;
            String[] f = line.substring(c + 1).trim().split("\\s+");
            if (f.length < 9) continue;
            rx += parse(f[0]); tx += parse(f[8]); any = true;
        }
        if (!any) return;
        long now = System.currentTimeMillis();
        if (prevRx >= 0 && now > prevNetAt && rx >= prevRx && tx >= prevTx) {
            double s = (now - prevNetAt) / 1000.0;
            rxRate = (rx - prevRx) / s;
            txRate = (tx - prevTx) / s;
        }
        prevRx = rx; prevTx = tx; prevNetAt = now;
    }

    // ---- snapshot parts ----
    public Map<String, Object> server() {
        Map<String, Object> s = new LinkedHashMap<>();
        int cores = Runtime.getRuntime().availableProcessors();
        s.put("cores", cores);
        s.put("cpuPercent", round1(cpuPercent));
        double l1 = -1, l5 = -1, l15 = -1;
        List<String> la = readLines("/proc/loadavg", 1);
        if (!la.isEmpty()) {
            String[] f = la.get(0).split("\\s+");
            if (f.length >= 3) { l1 = dbl(f[0]); l5 = dbl(f[1]); l15 = dbl(f[2]); }
        }
        if (l1 < 0) l1 = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        s.put("load1", round2(l1)); s.put("load5", round2(l5)); s.put("load15", round2(l15));

        Map<String, Long> mem = readMem();
        long total = mem.getOrDefault("MemTotal", 0L), avail = mem.getOrDefault("MemAvailable", 0L);
        s.put("ramTotal", total);
        s.put("ramUsed", Math.max(0, total - avail));
        s.put("ramPercent", total > 0 ? round1(100.0 * (total - avail) / total) : 0.0);
        long swapT = mem.getOrDefault("SwapTotal", 0L), swapF = mem.getOrDefault("SwapFree", 0L);
        s.put("swapTotal", swapT);
        s.put("swapUsed", Math.max(0, swapT - swapF));

        File root = new File("/");
        long dTotal = root.getTotalSpace(), dFree = root.getUsableSpace();
        s.put("diskTotal", dTotal);
        s.put("diskUsed", Math.max(0, dTotal - dFree));
        s.put("diskPercent", dTotal > 0 ? round1(100.0 * (dTotal - dFree) / dTotal) : 0.0);

        List<String> up = readLines("/proc/uptime", 1);
        if (!up.isEmpty()) s.put("uptimeSeconds", (long) dbl(up.get(0).split("\\s+")[0]));
        s.put("rxPerSec", Math.round(rxRate));
        s.put("txPerSec", Math.round(txRate));
        s.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        try { s.put("host", InetAddress.getLocalHost().getHostName()); } catch (Exception e) { s.put("host", "server"); }
        List<Map<String, Object>> h;
        synchronized (history) { h = new ArrayList<>(history); }
        s.put("history", h);
        return s;
    }

    public Map<String, Object> app(int serverPort) {
        Map<String, Object> a = new LinkedHashMap<>();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long used = mem.getHeapMemoryUsage().getUsed();
        long max = mem.getHeapMemoryUsage().getMax();
        if (max <= 0) max = mem.getHeapMemoryUsage().getCommitted();
        a.put("heapUsed", used);
        a.put("heapMax", max);
        a.put("heapPercent", max > 0 ? round1(100.0 * used / max) : 0.0);
        a.put("nonHeapUsed", mem.getNonHeapMemoryUsage().getUsed());
        ThreadMXBean th = ManagementFactory.getThreadMXBean();
        a.put("threads", th.getThreadCount());
        a.put("threadsPeak", th.getPeakThreadCount());
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        a.put("gcCount", gcCount);
        a.put("gcTimeMs", gcTime);
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        a.put("uptimeSeconds", rt.getUptime() / 1000L);
        a.put("java", System.getProperty("java.version"));
        a.put("port", serverPort);
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.UnixOperatingSystemMXBean u) {
                a.put("openFiles", u.getOpenFileDescriptorCount());
                a.put("maxFiles", u.getMaxFileDescriptorCount());
            }
            if (os instanceof com.sun.management.OperatingSystemMXBean x) {
                double pc = x.getProcessCpuLoad();
                a.put("processCpuPercent", pc < 0 ? 0.0 : round1(pc * 100.0));
            }
        } catch (RuntimeException | LinkageError ignored) { }
        for (String line : readLines("/proc/self/status", 60)) {
            if (line.startsWith("VmRSS:")) { a.put("memoryFromOs", parse(line.substring(6).trim().split("\\s+")[0]) * 1024L); break; }
        }
        a.put("requests", requestStats());
        return a;
    }

    // ---- helpers ----
    static List<String> readLines(String file, int max) {
        List<String> out = new ArrayList<>();
        try {
            Path p = Path.of(file);
            if (!Files.isReadable(p)) return out;
            try (var br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String l;
                while ((l = br.readLine()) != null && out.size() < max) out.add(l);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0L; }
    }

    static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); } catch (RuntimeException e) { return -1; }
    }

    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
