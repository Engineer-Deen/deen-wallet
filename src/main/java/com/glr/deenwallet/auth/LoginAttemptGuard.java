package com.glr.deenwallet.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small IP-level backstop for login attempts, including emails that do not
 * exist. Account-level counters remain persisted on the User record.
 */
@Slf4j
@Component
public class LoginAttemptGuard {
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Value("${app.auth.login.ip-max-failed-attempts:10}")
    private int maxFailures;
    @Value("${app.auth.login.ip-lockout-minutes:60}")
    private int lockoutMinutes;

    public Instant check(String key) {
        State state = states.get(key);
        if (state == null) return null;
        if (state.lockedUntil != null && state.lockedUntil.isAfter(Instant.now())) return state.lockedUntil;
        if (state.lockedUntil != null) states.remove(key);
        return null;
    }

    public Instant recordFailure(String key) {
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            state.failures++;
            if (state.failures >= maxFailures) {
                state.lockedUntil = Instant.now().plusSeconds(lockoutMinutes * 60L);
                log.warn("IP login guard activated for key {} until {}", key, state.lockedUntil);
                return state.lockedUntil;
            }
            return null;
        }
    }

    public void recordSuccess(String key) {
        states.remove(key);
    }

    private static final class State {
        int failures;
        Instant lockedUntil;
    }
}
