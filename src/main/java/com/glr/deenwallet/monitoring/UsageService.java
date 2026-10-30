package com.glr.deenwallet.monitoring;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds up what this server uses each month (data out, data in, storage, peaks) and stores it, so it survives restarts.
 * It also estimates what the usage would cost above Oracle's free allowance. The estimate is a guide only:
 * Oracle's own Cost Analysis page is the real bill.
 */
@Service
public class UsageService {

    @Value("${ORACLE_PLAN:free}")
    private String plan;

    @Value("${ORACLE_FREE_OUTBOUND_GB:10240}")
    private double freeOutboundGb;

    @Value("${ORACLE_FREE_STORAGE_GB:200}")
    private double freeStorageGb;

    @Value("${ORACLE_PRICE_PER_GB_OUTBOUND:0.0085}")
    private double priceOutbound;

    @Value("${ORACLE_PRICE_PER_GB_STORAGE_MONTH:0.0255}")
    private double priceStorage;

    @Value("${ORACLE_PAYG_START:}")
    private String paygStart;

    @PersistenceContext
    private EntityManager em;

    private long prevRx = -1, prevTx = -1;
    private double cpuPeak = 0, ramPeak = 0;

    /** Called by the 5-second sampler so peaks are not missed. */
    public synchronized void notePeaks(double cpu, double ram) {
        if (cpu > cpuPeak) cpuPeak = cpu;
        if (ram > ramPeak) ramPeak = ram;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    @Transactional
    public void collect() {
        long rx = 0, tx = 0;
        boolean any = false;
        for (String line : ServerMetricsService.readLines("/proc/net/dev", 40)) {
            int c = line.indexOf(':');
            if (c < 0 || line.substring(0, c).trim().equals("lo")) continue;
            String[] f = line.substring(c + 1).trim().split("\\s+");
            if (f.length < 9) continue;
            rx += ServerMetricsService.parse(f[0]);
            tx += ServerMetricsService.parse(f[8]);
            any = true;
        }
        long dRx = 0, dTx = 0;
        if (any) {
            if (prevRx >= 0 && rx >= prevRx && tx >= prevTx) { dRx = rx - prevRx; dTx = tx - prevTx; } // after a reboot the counters restart: skip that one step
            prevRx = rx; prevTx = tx;
        }
        File root = new File("/");
        long total = root.getTotalSpace(), used = Math.max(0, total - root.getUsableSpace());
        double cpu, ram;
        synchronized (this) { cpu = cpuPeak; ram = ramPeak; cpuPeak = 0; ramPeak = 0; }
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        em.createNativeQuery("INSERT INTO usage_monthly (month, tx_bytes, rx_bytes, disk_used, disk_total, ram_peak_pct, cpu_peak_pct, updated_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, now()) ON CONFLICT (month) DO UPDATE SET "
                        + "tx_bytes = usage_monthly.tx_bytes + EXCLUDED.tx_bytes, rx_bytes = usage_monthly.rx_bytes + EXCLUDED.rx_bytes, "
                        + "disk_used = GREATEST(usage_monthly.disk_used, EXCLUDED.disk_used), disk_total = EXCLUDED.disk_total, "
                        + "ram_peak_pct = GREATEST(usage_monthly.ram_peak_pct, EXCLUDED.ram_peak_pct), cpu_peak_pct = GREATEST(usage_monthly.cpu_peak_pct, EXCLUDED.cpu_peak_pct), updated_at = now()")
                .setParameter(1, month).setParameter(2, dTx).setParameter(3, dRx).setParameter(4, used).setParameter(5, total)
                .setParameter(6, Math.round(ram * 10) / 10.0).setParameter(7, Math.round(cpu * 10) / 10.0).executeUpdate();
    }

    private double cost(long tx, long diskUsed) {
        double outGb = tx / 1_073_741_824.0, diskGb = diskUsed / 1_073_741_824.0;
        double c = Math.max(0, outGb - freeOutboundGb) * priceOutbound + Math.max(0, diskGb - freeStorageGb) * priceStorage;
        return Math.round(c * 100) / 100.0;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean payg = "payg".equalsIgnoreCase(plan) || "paid".equalsIgnoreCase(plan);
        out.put("plan", payg ? "payg" : "free");
        out.put("paygStart", paygStart == null || paygStart.isBlank() ? null : paygStart);
        out.put("freeOutboundGb", freeOutboundGb);
        out.put("freeStorageGb", freeStorageGb);
        out.put("priceOutbound", priceOutbound);
        out.put("priceStorage", priceStorage);
        try {
            List<Map<String, Object>> months = new ArrayList<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT month, tx_bytes, rx_bytes, disk_used, disk_total, ram_peak_pct, cpu_peak_pct FROM usage_monthly ORDER BY month DESC LIMIT 12").getResultList()) {
                long tx = ((Number) r[1]).longValue(), du = ((Number) r[3]).longValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("month", r[0]);
                m.put("txBytes", tx);
                m.put("rxBytes", ((Number) r[2]).longValue());
                m.put("diskUsed", du);
                m.put("diskTotal", ((Number) r[4]).longValue());
                m.put("ramPeak", ((Number) r[5]).doubleValue());
                m.put("cpuPeak", ((Number) r[6]).doubleValue());
                m.put("estimatedCost", cost(tx, du));
                months.add(m);
            }
            out.put("months", months);
            out.put("available", true);
        } catch (RuntimeException e) {
            out.put("available", false);
        }
        return out;
    }
}
