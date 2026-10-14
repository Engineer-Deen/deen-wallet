package com.glr.deenwallet.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window, in-memory rate limiter for a single application instance.
 *
 * Public/authentication endpoints are limited per client IP.
 * Money and lookup endpoints are limited per authenticated user.
 * The Monime webhook remains exempt.
 *
 * For multiple application instances, use a shared limiter such as Redis.
 * Ensure the reverse proxy and forwarded-client-IP configuration are trusted.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(
            String id,
            String method,
            String prefix,
            int max,
            long windowSec,
            boolean perUser
    ) {}

    private static final List<Rule> RULES = List.of(
            new Rule("login", "POST", "/api/auth/login", 10, 300, false),
            new Rule("register", "POST", "/api/auth/register", 5, 3600, false),
            new Rule("otp", "POST", "/api/auth/resend-otp", 5, 900, false),
            new Rule("forgotpw", "POST", "/api/auth/forgot-password", 5, 900, false),
            new Rule("forgotpin", "POST", "/api/auth/forgot-pin", 5, 900, false),
            new Rule("confirm", "POST", "/api/auth/confirm-email", 10, 900, false),
            new Rule("pinreset", "POST", "/api/auth/verify-pin-reset-code", 10, 900, false),
            new Rule("pinreset2", "POST", "/api/auth/reset-pin", 10, 900, false),
            new Rule("pwreset", "POST", "/api/auth/reset-password", 10, 900, false),
            new Rule("refresh", "POST", "/api/auth/refresh", 30, 60, false),
            new Rule("bio", "POST", "/api/auth/biometric", 20, 60, false),

            // Admin login retains its existing account-lockout protection.
            new Rule("adminrecovery", "POST", "/api/admin/auth/recovery", 10, 300, false),
            new Rule("adminapk-authorize", "POST", "/api/app/admin-android-update/authorize", 5, 300, false),
            new Rule("adminrefresh", "POST", "/api/admin/auth/refresh", 30, 60, false),
            new Rule("adminforgotpw", "POST", "/api/admin/auth/forgot-password", 5, 900, false),
            new Rule("adminresetpw", "POST", "/api/admin/auth/reset-password", 10, 900, false),
            new Rule("adminforgotpin", "POST", "/api/admin/auth/forgot-pin", 5, 900, false),
            new Rule("adminpincode", "POST", "/api/admin/auth/verify-pin-reset-code", 10, 900, false),
            new Rule("adminresetpin", "POST", "/api/admin/auth/reset-pin", 10, 900, false),

            new Rule("pin", "POST", "/api/users/me/verify-pin", 10, 300, true),
            new Rule("pay", "POST", "/api/transactions", 6, 60, true),
            new Rule("bank", "POST", "/api/bank-transfers", 6, 60, true),
            new Rule("waec", "POST", "/api/waec/purchases", 6, 60, true),
            new Rule("waecquote", "GET", "/api/waec/quote", 60, 60, true),
            new Rule("lookup", "GET", "/api/accounts/detect-provider", 20, 60, true),
            new Rule("verifyacc", "GET", "/api/bank-transfers/verify-account", 20, 60, true),
            new Rule("fee", "GET", "/api/transactions/preview-fee", 60, 60, true)
    );

    private static final int GLOBAL_MAX_PER_MIN = 300;
    private static final int MAX_TRACKED_KEYS = 200_000;
    private static final long WINDOW_FULL_RETRY_SECONDS = 60L;

    private static final class Window {
        long start;
        long windowMs;
        int count;

        Window(long start, long windowMs) {
            this.start = start;
            this.windowMs = windowMs;
            this.count = 0;
        }
    }

    private final ConcurrentHashMap<String, Window> windows =
            new ConcurrentHashMap<>();
    private final AtomicInteger trackedKeys = new AtomicInteger();

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest req) {
        String path = req.getRequestURI();

        return !path.startsWith("/api/")
                || path.startsWith("/api/v1/webhooks/")
                || "OPTIONS".equals(req.getMethod());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest req,
            @NonNull HttpServletResponse res,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String ip = req.getRemoteAddr();
        String user = currentUser();
        String path = req.getRequestURI();
        String method = req.getMethod();

        for (Rule rule : RULES) {
            if (rule.method().equals(method)
                    && matchesPath(path, rule.prefix())) {

                String who = rule.perUser() && user != null
                        ? "u:" + user
                        : "ip:" + ip;

                long retry = hit(
                        rule.id() + "|" + who,
                        rule.max(),
                        rule.windowSec()
                );

                if (retry > 0) {
                    reject(res, retry);
                    return;
                }
            }
        }

        long retry = hit(
                "global|" + (user != null ? "u:" + user : "ip:" + ip),
                GLOBAL_MAX_PER_MIN,
                60
        );

        if (retry > 0) {
            reject(res, retry);
            return;
        }

        chain.doFilter(req, res);
    }

    /*
     * Match an exact route prefix or a child path, avoiding accidental
     * matches such as /api/transactionsXYZ.
     */
    private boolean matchesPath(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * Returns 0 when allowed; otherwise seconds until retry is permitted.
     * Per-key map computation keeps concurrent requests for one key atomic
     * without serializing all API requests behind a single global lock.
     */
    private long hit(String key, int max, long windowSec) {
        long now = System.currentTimeMillis();
        if (trackedKeys.get() >= MAX_TRACKED_KEYS) {
            removeExpiredWindows(now);
        }

        AtomicLong retryAfter = new AtomicLong(0L);
        windows.compute(key, (ignored, existing) -> {
            Window window = existing;
            if (window == null) {
                int count = trackedKeys.incrementAndGet();
                if (count > MAX_TRACKED_KEYS) {
                    trackedKeys.decrementAndGet();
                    retryAfter.set(WINDOW_FULL_RETRY_SECONDS);
                    return null;
                }
                window = new Window(now, windowSec * 1000L);
            }

            if (now - window.start >= window.windowMs || now < window.start) {
                window.start = now;
                window.windowMs = windowSec * 1000L;
                window.count = 0;
            }

            window.count++;
            if (window.count > max) {
                long remainingMs = window.start + window.windowMs - now;
                retryAfter.set(Math.max(1, (remainingMs + 999) / 1000));
            }
            return window;
        });
        return retryAfter.get();
    }

    private String currentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof String principal
                && !"anonymousUser".equals(principal)
                ? principal
                : null;
    }

    private void reject(
            HttpServletResponse response,
            long retryAfter
    ) throws IOException {

        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"message\":\"Too many requests. Please slow down and try again shortly.\"}"
        );
    }

    private void removeExpiredWindows(long now) {
        windows.forEach((key, observed) -> windows.computeIfPresent(key, (ignored, current) -> {
            long start = current.start;
            if (now < start || now - start >= current.windowMs) {
                trackedKeys.decrementAndGet();
                return null;
            }
            return current;
        }));
    }

    /** Remove expired counters; active counters are never globally cleared. */
    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        removeExpiredWindows(System.currentTimeMillis());
    }
}