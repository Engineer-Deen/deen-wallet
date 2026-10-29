package com.glr.deenwallet.monitoring;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Collects security events quietly in the background (never slows a request) and summarises them for the admin Metrics tab. */
@Service
public class SecurityEventService {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventService.class);
    private static final int QUEUE_LIMIT = 3000;
    private static final int PER_IP_KIND_PER_BATCH = 30;

    private record Pending(String ip, String kind, String method, String path, int status, String ua) { }

    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();

    @PersistenceContext
    private EntityManager em;

    public void record(String ip, String kind, String method, String path, int status, String userAgent) {
        if (queued.get() >= QUEUE_LIMIT) return; // under a huge attack we keep the server healthy instead of storing every hit
        queue.add(new Pending(cut(ip, 64), kind, cut(method, 10), cut(path, 200), status, cut(userAgent, 160)));
        queued.incrementAndGet();
    }

    private static String cut(String s, int n) {
        if (s == null) return null;
        s = s.replace('\u0000', ' ');
        return s.length() <= n ? s : s.substring(0, n);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    @Transactional
    public void flush() {
        Map<String, Integer> perKey = new HashMap<>();
        int done = 0;
        Pending p;
        while (done < 600 && (p = queue.poll()) != null) {
            queued.decrementAndGet();
            done++;
            String key = p.ip() + "|" + p.kind();
            int n = perKey.merge(key, 1, Integer::sum);
            if (n > PER_IP_KIND_PER_BATCH) continue;
            try {
                em.createNativeQuery("INSERT INTO security_events (ip, kind, method, path, status, user_agent) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                        .setParameter(1, p.ip()).setParameter(2, p.kind()).setParameter(3, p.method())
                        .setParameter(4, p.path()).setParameter(5, p.status()).setParameter(6, p.ua())
                        .executeUpdate();
            } catch (RuntimeException e) {
                log.debug("Could not store security event: {}", e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public void cleanup() {
        int n = em.createNativeQuery("DELETE FROM security_events WHERE created_at < now() - interval '14 days'").executeUpdate();
        if (n > 0) log.info("Removed {} old security events", n);
    }

    static String iso(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i.toString();
        if (o instanceof Timestamp t) return t.toInstant().toString();
        if (o instanceof OffsetDateTime d) return d.toInstant().toString();
        return o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** Last 24 hours: totals, worst IP addresses, hour-by-hour counts and the newest events. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Long> byKind = new LinkedHashMap<>();
            long total = 0;
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT kind, COUNT(*) FROM security_events WHERE created_at > now() - interval '24 hours' GROUP BY kind ORDER BY 2 DESC").getResultList()) {
                byKind.put(String.valueOf(r[0]), num(r[1]));
                total += num(r[1]);
            }
            out.put("total24h", total);
            out.put("byKind", byKind);

            Map<String, Map<String, Long>> kindsByIp = new HashMap<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT ip, kind, COUNT(*) FROM security_events WHERE created_at > now() - interval '24 hours' GROUP BY ip, kind").getResultList()) {
                kindsByIp.computeIfAbsent(String.valueOf(r[0]), k -> new LinkedHashMap<>()).put(String.valueOf(r[1]), num(r[2]));
            }
            List<Map<String, Object>> top = new ArrayList<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT ip, COUNT(*) AS n, MIN(created_at), MAX(created_at) FROM security_events "
                            + "WHERE created_at > now() - interval '24 hours' GROUP BY ip ORDER BY n DESC LIMIT 10").getResultList()) {
                String ip = String.valueOf(r[0]);
                long n = num(r[1]);
                Map<String, Long> kinds = kindsByIp.getOrDefault(ip, Map.of());
                long scans = kinds.getOrDefault("SCAN", 0L) + kinds.getOrDefault("ADMIN_PROBE", 0L);
                long logins = kinds.getOrDefault("LOGIN_FAILED", 0L) + kinds.getOrDefault("PIN_FAILED", 0L);
                String level = (n >= 100 || scans >= 10 || logins >= 15) ? "high" : (n >= 20 || scans >= 3 || logins >= 6) ? "medium" : "low";
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ip", ip);
                m.put("count", n);
                m.put("kinds", kinds);
                m.put("firstSeen", iso(r[2]));
                m.put("lastSeen", iso(r[3]));
                m.put("level", level);
                top.add(m);
            }
            out.put("topIps", top);

            List<Long> hours = new ArrayList<>();
            Map<Long, Long> byHour = new HashMap<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT FLOOR(EXTRACT(EPOCH FROM (now() - created_at)) / 3600) AS h, COUNT(*) FROM security_events "
                            + "WHERE created_at > now() - interval '24 hours' GROUP BY 1").getResultList()) {
                byHour.put(num(r[0]), num(r[1]));
            }
            for (long h = 23; h >= 0; h--) hours.add(byHour.getOrDefault(h, 0L)); // oldest first
            out.put("perHour", hours);

            List<Map<String, Object>> recent = new ArrayList<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT created_at, ip, kind, method, path, status FROM security_events ORDER BY id DESC LIMIT 25").getResultList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", iso(r[0]));
                m.put("ip", r[1]);
                m.put("kind", r[2]);
                m.put("method", r[3]);
                m.put("path", r[4]);
                m.put("status", r[5]);
                recent.add(m);
            }
            out.put("recent", recent);
            out.put("available", true);
        } catch (RuntimeException e) {
            out.put("available", false);
            out.put("error", "Security events are not available yet (" + e.getClass().getSimpleName() + ").");
        }
        return out;
    }
}
