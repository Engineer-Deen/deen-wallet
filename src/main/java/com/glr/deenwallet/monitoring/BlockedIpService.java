package com.glr.deenwallet.monitoring;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** The list of addresses an admin blocked. The check on each request is a plain in-memory lookup. */
@Service
public class BlockedIpService {

    private static final Pattern V4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern V6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");
    private static final long FOREVER = Long.MAX_VALUE;

    /** ip -> time (ms) the block ends. */
    private volatile Map<String, Long> blocked = Map.of();
    private final ConcurrentHashMap<String, AtomicLong> hits = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager em;

    public boolean isBlocked(String ip) {
        if (ip == null) return false;
        Long until = blocked.get(ip);
        return until != null && System.currentTimeMillis() < until;
    }

    public void countHit(String ip) {
        if (hits.size() > 20_000) hits.clear();
        hits.computeIfAbsent(ip, k -> new AtomicLong()).incrementAndGet();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 4_000)
    @Transactional(readOnly = true)
    public void reload() {
        try {
            reloadNow();
        } catch (RuntimeException ignored) {
            // keep the old list if the database is busy
        }
    }

    @SuppressWarnings("unchecked")
    private void reloadNow() {
        Map<String, Long> next = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT ip, CAST(EXTRACT(EPOCH FROM expires_at) * 1000 AS bigint) FROM blocked_ips WHERE expires_at IS NULL OR expires_at > now()").getResultList()) {
            next.put(String.valueOf(r[0]), r[1] instanceof Number n ? n.longValue() : FOREVER);
        }
        blocked = Map.copyOf(next);
    }

    /** Why this address must not be blocked, or null when it is fine to block. */
    static String refusal(String ip, String yourIp) {
        if (ip == null || ip.isBlank()) return "Empty address";
        boolean ok = false;
        var m = V4.matcher(ip);
        if (m.matches()) {
            ok = true;
            for (int i = 1; i <= 4; i++) if (Integer.parseInt(m.group(i)) > 255) ok = false;
        } else if (ip.contains(":") && V6.matcher(ip).matches()) {
            ok = true;
        }
        if (!ok) return "Not a valid address";
        if (ip.equals(yourIp)) return "This is your own address: blocking it would lock you out";
        try {
            InetAddress a = InetAddress.getByName(ip); // an address written in digits: no name lookup happens
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
                return "Private or local address";
            }
        } catch (Exception e) {
            return "Not a valid address";
        }
        return null;
    }

    @Transactional
    public Map<String, Object> block(List<String> ips, Integer hours, String reason, String by, String yourIp) {
        int done = 0;
        List<Map<String, String>> skipped = new ArrayList<>();
        String why = reason == null ? "" : (reason.length() > 200 ? reason.substring(0, 200) : reason);
        int h = hours == null ? 0 : Math.max(1, Math.min(hours, 24 * 365));
        for (String raw : ips == null ? List.<String>of() : ips.stream().limit(200).toList()) {
            String ip = raw == null ? "" : raw.trim();
            String no = refusal(ip, yourIp);
            if (no != null) {
                Map<String, String> s = new LinkedHashMap<>();
                s.put("ip", ip.length() > 64 ? ip.substring(0, 64) : ip);
                s.put("why", no);
                skipped.add(s);
                continue;
            }
            if (hours == null) {
                em.createNativeQuery("INSERT INTO blocked_ips (ip, reason, blocked_by, created_at, expires_at) VALUES (?1, ?2, ?3, now(), NULL) "
                                + "ON CONFLICT (ip) DO UPDATE SET reason = EXCLUDED.reason, blocked_by = EXCLUDED.blocked_by, created_at = now(), expires_at = NULL")
                        .setParameter(1, ip).setParameter(2, why).setParameter(3, by).executeUpdate();
            } else {
                em.createNativeQuery("INSERT INTO blocked_ips (ip, reason, blocked_by, created_at, expires_at) VALUES (?1, ?2, ?3, now(), now() + make_interval(hours => ?4)) "
                                + "ON CONFLICT (ip) DO UPDATE SET reason = EXCLUDED.reason, blocked_by = EXCLUDED.blocked_by, created_at = now(), expires_at = EXCLUDED.expires_at")
                        .setParameter(1, ip).setParameter(2, why).setParameter(3, by).setParameter(4, h).executeUpdate();
            }
            done++;
        }
        reloadNow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocked", done);
        out.put("skipped", skipped);
        return out;
    }

    @Transactional
    public int unblock(List<String> ips) {
        int n = 0;
        for (String raw : ips == null ? List.<String>of() : ips.stream().limit(500).toList()) {
            if (raw == null) continue;
            n += em.createNativeQuery("DELETE FROM blocked_ips WHERE ip = ?1").setParameter(1, raw.trim()).executeUpdate();
        }
        reloadNow();
        return n;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT ip, reason, blocked_by, created_at, expires_at FROM blocked_ips WHERE expires_at IS NULL OR expires_at > now() ORDER BY created_at DESC LIMIT 500").getResultList()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String ip = String.valueOf(r[0]);
                m.put("ip", ip);
                m.put("reason", r[1]);
                m.put("by", r[2]);
                m.put("at", SecurityEventService.iso(r[3]));
                m.put("until", SecurityEventService.iso(r[4]));
                AtomicLong h = hits.get(ip);
                m.put("hits", h == null ? 0L : h.get());
                out.add(m);
            }
        } catch (RuntimeException ignored) {
            // table not there yet
        }
        return out;
    }
}
