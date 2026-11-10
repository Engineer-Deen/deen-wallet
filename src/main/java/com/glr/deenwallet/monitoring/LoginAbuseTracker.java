package com.glr.deenwallet.monitoring;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The decision part of the automatic ban (no Spring, no database, so it can be tested on its own).
 * It aims at addresses that are NOT customers: it remembers, per visitor address, the failed password logins of the last
 * 10 minutes and says when the pattern is a bot and not a person:
 *   - tried 4 or more DIFFERENT emails that are not registered here (a person mistypes one or two, a bot walks a list), or
 *   - posted 2 or more times to a login address that does not exist (a real app or page never does), or
 *   - failed on 8 or more DIFFERENT registered accounts (a customer forgetting a password, or relatives sharing one
 *     connection, stay far below this).
 * Even then the guard does not block an address that a signed-in customer has used (see KnownClientIps).
 */
final class LoginAbuseTracker {

    static final long WINDOW_MS = 10 * 60_000L;
    static final int MAX_UNREGISTERED = 4;
    static final int MAX_FAKE_PATH_HITS = 2;
    static final int MAX_REGISTERED = 8;
    private static final int MAX_EVENTS_PER_IP = 40;
    private static final int MAX_IPS = 20_000;

    /** The only two password-login addresses that really exist. Any other ".../login" is something a bot made up. */
    private static final Set<String> REAL_LOGIN_PATHS = Set.of("/api/auth/login", "/api/admin/auth/login");

    private record Ev(long t, boolean fakePath, String unregistered, String registered) { }

    private final Map<String, ArrayDeque<Ev>> byIp = new HashMap<>();

    static String normalize(String path) {
        String p = path == null ? "" : path.toLowerCase();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.length() > 100 ? p.substring(0, 100) : p;
    }

    /**
     * Only these requests count: a failed password login (kind LOGIN_UNKNOWN = email not registered, LOGIN_FAILED = registered
     * email with a wrong password), or any request to a login address that does not exist. The fingerprint login is ignored.
     */
    static boolean countable(String method, String path, String kind) {
        if (!"POST".equals(method) || path == null || kind == null) return false;
        String p = normalize(path);
        if (!p.endsWith("/login") || p.contains("/biometric/")) return false;
        if (kind.equals("LOGIN_UNKNOWN") || kind.equals("LOGIN_FAILED")) return true;
        return kind.equals("REJECTED") && !REAL_LOGIN_PATHS.contains(p);
    }

    /** Notes one failed try. Returns why this address looks like a bot now, or null. */
    synchronized String record(String ip, String path, String kind, String account, long now) {
        if (byIp.size() >= MAX_IPS && !byIp.containsKey(ip)) purge(now);
        ArrayDeque<Ev> q = byIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst().t() < now - WINDOW_MS) q.pollFirst();
        String p = normalize(path);
        String acct = account == null || account.isBlank() ? null : account.trim().toLowerCase();
        q.addLast(new Ev(now, !REAL_LOGIN_PATHS.contains(p),
                "LOGIN_UNKNOWN".equals(kind) ? acct : null,
                "LOGIN_FAILED".equals(kind) ? acct : null));
        if (q.size() > MAX_EVENTS_PER_IP) q.pollFirst();
        int fake = 0;
        Set<String> unregistered = new HashSet<>();
        Set<String> registered = new HashSet<>();
        for (Ev e : q) {
            if (e.fakePath()) fake++;
            if (e.unregistered() != null) unregistered.add(e.unregistered());
            if (e.registered() != null) registered.add(e.registered());
        }
        if (fake >= MAX_FAKE_PATH_HITS) {
            return "posted " + fake + " times to login addresses that do not exist, within 10 minutes";
        }
        if (unregistered.size() >= MAX_UNREGISTERED) {
            return "tried " + unregistered.size() + " different emails that are not registered, within 10 minutes";
        }
        if (registered.size() >= MAX_REGISTERED) {
            return "failed to sign in to " + registered.size() + " different registered accounts, within 10 minutes";
        }
        return null;
    }

    synchronized void forget(String ip) {
        byIp.remove(ip);
    }

    private void purge(long now) {
        byIp.values().removeIf(q -> q.isEmpty() || q.peekLast().t() < now - WINDOW_MS);
        if (byIp.size() >= MAX_IPS) byIp.clear(); // a flood from many addresses: forget, never grow without limit
    }
}
