package com.glr.deenwallet.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automatic temporary ban for an address that behaves like a bot (see LoginAbuseTracker). Built to protect customers first:
 *   - an address that a signed-in customer or admin used in the last 30 days is NEVER blocked (shared mobile networks);
 *     it is only reported on the dashboard ("Looked like a bot, but a real customer uses this address");
 *   - it starts in WATCH mode: it only reports "Would be blocked automatically" and blocks nothing, until you switch it on;
 *   - it never blocks Cloudflare, private/local or invalid addresses (BlockedIpService refuses those), or AUTOBAN_ALLOW_IPS.
 * A ban uses the same blocked list as the admin Block button, so every ban can be seen and lifted in the admin Metrics tab.
 *
 * Settings (all optional, in deenwallet.env):
 *   AUTOBAN_MODE=watch|on|off   watch (default) only reports, on also blocks, off does nothing
 *   AUTOBAN_HOURS=1             length of a first ban in hours (a repeat within 24 hours is blocked for 24 hours)
 *   AUTOBAN_ALLOW_IPS=1.2.3.4,5.6.7.8   addresses that are never auto-banned (your office / your own phone)
 *   AUTOBAN_ENABLED=false       old switch, still understood: same as AUTOBAN_MODE=off
 */
@Component
public class LoginAbuseGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginAbuseGuard.class);
    private static final long REPEAT_MS = 24L * 3600_000L;
    private static final long NOTE_EVERY_MS = 10L * 60_000L;
    private static final long TOUCH_EVERY_MS = 3_600_000L;

    private final BlockedIpService blocked;
    private final SecurityEventService events;
    private final KnownClientIps known;
    private final LoginAbuseTracker tracker = new LoginAbuseTracker();
    private final Map<String, Long> lastBan = new ConcurrentHashMap<>();
    private final Map<String, Long> lastNote = new ConcurrentHashMap<>();
    private final Map<String, Long> goodSeen = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "autoban");
        t.setDaemon(true);
        return t;
    });

    @Value("${AUTOBAN_MODE:watch}")
    private String mode;

    @Value("${AUTOBAN_ENABLED:true}")
    private boolean enabled;

    @Value("${AUTOBAN_HOURS:1}")
    private int hours;

    @Value("${AUTOBAN_ALLOW_IPS:}")
    private String allowIps;

    public LoginAbuseGuard(BlockedIpService blocked, SecurityEventService events, KnownClientIps known) {
        this.blocked = blocked;
        this.events = events;
        this.known = known;
    }

    private String effectiveMode() {
        if (!enabled) return "off";
        String m = mode == null ? "watch" : mode.trim().toLowerCase();
        return m.equals("on") || m.equals("off") ? m : "watch";
    }

    /** A request with a valid sign-in token succeeded from this address: remember it (one database write per hour at most). */
    public void markGood(String ip) {
        try {
            if (ip == null || ip.isBlank()) return;
            long now = System.currentTimeMillis();
            Long seen = goodSeen.get(ip);
            if (seen != null && now - seen < TOUCH_EVERY_MS) return;
            if (goodSeen.size() > 50_000) goodSeen.clear();
            goodSeen.put(ip, now);
            pool.execute(() -> {
                try {
                    known.touch(ip);
                } catch (RuntimeException e) {
                    goodSeen.remove(ip);
                    log.warn("Could not remember customer address: {}", e.toString());
                }
            });
        } catch (RuntimeException ignored) {
            // never break a request
        }
    }

    /** Called by the security monitor for every request that was classified as an attack record. Never throws. */
    public void onFailure(String ip, String method, String path, String kind, String account, int status, String userAgent) {
        try {
            String m = effectiveMode();
            if (m.equals("off") || ip == null || !LoginAbuseTracker.countable(method, path, kind)) return;
            String reason = tracker.record(ip, path, kind, account, System.currentTimeMillis());
            if (reason == null) return;
            tracker.forget(ip);
            if (isAllowed(ip) || BlockedIpService.refusal(ip, null) != null) return; // Cloudflare, private, invalid, or yours
            pool.execute(() -> decide(ip, m, reason, method, path, status, userAgent));
        } catch (RuntimeException e) {
            log.warn("Auto-ban check failed: {}", e.toString());
        }
    }

    private void decide(String ip, String m, String reason, String method, String path, int status, String ua) {
        try {
            if (goodSeen.containsKey(ip) || known.isKnown(ip)) {
                note(ip, "AUTO_BAN_SKIPPED", method, path, status, ua, reason + " - NOT blocked: a signed-in customer used this address recently");
                return;
            }
            if (m.equals("watch")) {
                note(ip, "AUTO_BAN_WATCH", method, path, status, ua, reason + " - would be blocked (watch mode)");
                return;
            }
            long now = System.currentTimeMillis();
            Long before = lastBan.get(ip);
            int h = before != null && now - before < REPEAT_MS ? 24 : Math.max(1, Math.min(hours, 24 * 30));
            Map<String, Object> r = blocked.block(List.of(ip), h, "Auto-ban: " + reason, "auto-ban", null);
            Object done = r.get("blocked");
            if (done instanceof Number n && n.intValue() > 0) {
                if (lastBan.size() > 5000) lastBan.clear();
                lastBan.put(ip, now);
                log.warn("Auto-ban: {} blocked for {} hour(s) - {}", ip, h, reason);
                events.record(ip, "AUTO_BAN", method, path, status, ua, reason + " - blocked " + h + "h");
            }
        } catch (RuntimeException e) {
            log.warn("Auto-ban of {} failed: {}", ip, e.toString());
        }
    }

    /** One dashboard note per address, kind and 10 minutes. */
    private void note(String ip, String kind, String method, String path, int status, String ua, String detail) {
        long now = System.currentTimeMillis();
        String key = ip + "|" + kind;
        Long before = lastNote.get(key);
        if (before != null && now - before < NOTE_EVERY_MS) return;
        if (lastNote.size() > 5000) lastNote.clear();
        lastNote.put(key, now);
        log.info("Auto-ban {}: {} - {}", kind, ip, detail);
        events.record(ip, kind, method, path, status, ua, detail);
    }

    private boolean isAllowed(String ip) {
        if (allowIps == null || allowIps.isBlank()) return false;
        Set<String> s = new HashSet<>();
        for (String a : allowIps.split(",")) if (!a.isBlank()) s.add(a.trim());
        return s.contains(ip);
    }
}
