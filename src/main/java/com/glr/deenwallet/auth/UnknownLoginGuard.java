package com.glr.deenwallet.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers wrong-password tries for emails that are NOT registered, with the same rules as a real account
 * (5 wrong tries inside 15 minutes pause the login for 5 minutes). This way a bot sees the very same replies
 * for a registered and an unregistered email, so it cannot use the replies to list who has an account.
 * Memory only (it is a small, bounded map; a restart simply forgets it).
 */
final class UnknownLoginGuard {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK = Duration.ofMinutes(5);
    private static final int MAX_KEYS = 20_000;

    private static final class Entry {
        int fails;
        Instant last;
        Instant lockedUntil;
    }

    private final Map<String, Entry> map = new HashMap<>();

    /** The end of the pause when this email is paused right now, otherwise null. */
    synchronized Instant lockedUntil(String email, Instant now) {
        Entry e = map.get(email);
        if (e == null) return null;
        if (e.lockedUntil != null && e.lockedUntil.isAfter(now)) return e.lockedUntil;
        return null;
    }

    /** Counts one wrong try. Returns the end of the pause if this try started one, otherwise null. */
    synchronized Instant fail(String email, Instant now) {
        if (map.size() >= MAX_KEYS && !map.containsKey(email)) purge(now);
        Entry e = map.computeIfAbsent(email, k -> new Entry());
        boolean lockOver = e.lockedUntil != null && !e.lockedUntil.isAfter(now);
        boolean stale = e.last != null && e.last.plus(WINDOW).isBefore(now);
        if (lockOver || stale) {
            e.fails = 0;
            e.lockedUntil = null;
        }
        e.fails++;
        e.last = now;
        if (e.fails >= MAX_FAILURES) e.lockedUntil = now.plus(LOCK);
        return e.lockedUntil != null && e.lockedUntil.isAfter(now) ? e.lockedUntil : null;
    }

    private void purge(Instant now) {
        map.values().removeIf(e -> e.last == null || e.last.plus(WINDOW).isBefore(now));
        if (map.size() >= MAX_KEYS) map.clear(); // a flood of made-up emails: forget, never grow without limit
    }
}
