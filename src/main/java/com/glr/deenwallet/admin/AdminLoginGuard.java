package com.glr.deenwallet.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Temporary pauses for the admin password login (memory only, so a pause can never become permanent):
 *   - the same address getting the same admin email wrong 2 times: that ADDRESS is paused for that email for 15 minutes,
 *     so a stranger can never stop the real administrator from signing in from somewhere else;
 *   - 8 wrong tries on one email from ANY addresses within 30 minutes (many bots at once): sign-in for that email is paused
 *     for 30 minutes and the owner is told by email. The restore-access options (email code / passkey) still work.
 * A try made during a pause is refused without being counted, so repeated tries cannot make the pause longer.
 * Emails that are not administrators are counted the same way, so the replies never show who is an administrator.
 */
final class AdminLoginGuard {

    static final int PAIR_MAX = 2;
    static final Duration PAIR_WINDOW = Duration.ofMinutes(15);
    static final Duration PAIR_PAUSE = Duration.ofMinutes(15);
    static final int ACCOUNT_MAX = 8;
    static final Duration ACCOUNT_WINDOW = Duration.ofMinutes(30);
    static final Duration ACCOUNT_PAUSE = Duration.ofMinutes(30);
    private static final int MAX_KEYS = 20_000;

    private static final class Counter {
        int fails;
        Instant last;
        Instant pausedUntil;
    }

    /** until: the end of the pause that applies now (or null); accountJustPaused: this try started the account-wide pause. */
    record Outcome(Instant until, boolean accountJustPaused) { }

    private final Map<String, Counter> pairs = new HashMap<>();
    private final Map<String, Counter> accounts = new HashMap<>();

    private static String key(String account, String ip) {
        return account + "|" + ip;
    }

    private static Instant active(Counter c, Instant now) {
        return c != null && c.pausedUntil != null && c.pausedUntil.isAfter(now) ? c.pausedUntil : null;
    }

    /** The end of the pause that applies to this email from this address right now, or null. */
    synchronized Instant pausedUntil(String account, String ip, Instant now) {
        Instant a = active(pairs.get(key(account, ip)), now);
        Instant b = active(accounts.get(account), now);
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** Counts one wrong password. */
    synchronized Outcome fail(String account, String ip, Instant now) {
        if (pairs.size() >= MAX_KEYS) purge(pairs, now);
        if (accounts.size() >= MAX_KEYS) purge(accounts, now);
        bump(pairs, key(account, ip), PAIR_WINDOW, PAIR_MAX, PAIR_PAUSE, now);
        boolean accountPaused = bump(accounts, account, ACCOUNT_WINDOW, ACCOUNT_MAX, ACCOUNT_PAUSE, now);
        Instant until = pausedUntil(account, ip, now);
        return new Outcome(until, accountPaused);
    }

    /** A correct password from this address: forget its earlier mistakes. */
    synchronized void success(String account, String ip) {
        pairs.remove(key(account, ip));
    }

    /** Returns true when this try started a new pause. */
    private static boolean bump(Map<String, Counter> map, String k, Duration window, int max, Duration pause, Instant now) {
        Counter c = map.computeIfAbsent(k, x -> new Counter());
        boolean pauseOver = c.pausedUntil != null && !c.pausedUntil.isAfter(now);
        boolean stale = c.last != null && c.last.plus(window).isBefore(now);
        if (pauseOver || stale) {
            c.fails = 0;
            c.pausedUntil = null;
        }
        c.fails++;
        c.last = now;
        if (c.fails >= max && c.pausedUntil == null) {
            c.pausedUntil = now.plus(pause);
            return true;
        }
        return false;
    }

    private static void purge(Map<String, Counter> map, Instant now) {
        map.values().removeIf(c -> (c.pausedUntil == null || !c.pausedUntil.isAfter(now))
                && (c.last == null || c.last.plus(ACCOUNT_WINDOW).isBefore(now)));
        if (map.size() >= MAX_KEYS) map.clear(); // a flood of made-up emails: forget, never grow without limit
    }
}
