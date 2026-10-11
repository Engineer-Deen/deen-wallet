package com.glr.deenwallet.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Fixed-window, in-memory rate limiter (single instance). Public/auth endpoints are limited per
 * client IP; money and lookup endpoints per authenticated user. The Monime webhook is exempt.
 * For several app instances, move the counters to Redis.
 * Client IP comes from request.getRemoteAddr(): set server.forward-headers-strategy=native and make
 * your reverse proxy OVERWRITE X-Forwarded-For, otherwise every user shares the proxy's IP.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String id, String method, String prefix, int max, long windowSec, boolean perUser) {}

    // IP-based rules (perUser=false) are sized for SEVERAL real people sharing one public IP (mobile-
    // network address sharing, family/office Wi-Fi). Guessing is stopped per ACCOUNT instead: login and
    // PIN lockouts, OTP cooldown and attempt limits. Money and lookup rules are per user and stay strict.
    private static final List<Rule> RULES = List.of(
            new Rule("login",     "POST", "/api/auth/login",                 30, 300, false),
            new Rule("register",  "POST", "/api/auth/register",              20, 3600, false),
            new Rule("otp",       "POST", "/api/auth/resend-otp",            20, 900, false),
            new Rule("forgotpw",  "POST", "/api/auth/forgot-password",       15, 900, false),
            new Rule("forgotpin", "POST", "/api/auth/forgot-pin",            15, 900, false),
            new Rule("confirm",   "POST", "/api/auth/confirm-email",         30, 900, false),
            new Rule("pinreset",  "POST", "/api/auth/verify-pin-reset-code", 30, 900, false),
            new Rule("pinreset2", "POST", "/api/auth/reset-pin",             30, 900, false),
            new Rule("pwreset",   "POST", "/api/auth/reset-password",        30, 900, false),
            new Rule("refresh",   "POST", "/api/auth/refresh",               60, 60, false),
            new Rule("bio",       "POST", "/api/auth/biometric",             40, 60, false),
            // Admin LOGIN is intentionally NOT limited here: its protection is the account lockout
            // (2 wrong passwords -> email verification), and a limiter must never cut in before
            // that flow. Recovery (emails an OTP) keeps a cap; token refresh gets its own bucket.
            new Rule("adminrecovery", "POST", "/api/admin/auth/recovery", 10, 300, false),
            new Rule("adminrefresh",  "POST", "/api/admin/auth/refresh",  30, 60, false),
            new Rule("pin",       "POST", "/api/users/me/verify-pin",        10, 300, true),
            new Rule("pay",       "POST", "/api/transactions",                6, 60, true),
            new Rule("bank",      "POST", "/api/bank-transfers",              6, 60, true),
            new Rule("lookup",    "GET",  "/api/accounts/detect-provider",   20, 60, true),
            new Rule("verifyacc", "GET",  "/api/bank-transfers/verify-account", 20, 60, true),
            new Rule("fee",       "GET",  "/api/transactions/preview-fee",   60, 60, true)
    );
    private static final int GLOBAL_MAX_PER_MIN = 300;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    // Startup self-check: logs the client IP this limiter sees for the first few requests, so a wrong
    // proxy setup (everyone appearing as one address) is visible immediately.
    private final AtomicInteger ipCheckLogs = new AtomicInteger(6);

    private static final class Window { long start; int count; }
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest req) {
        String p = req.getRequestURI();
        return !p.startsWith("/api/") || p.startsWith("/api/v1/webhooks/") || "OPTIONS".equals(req.getMethod())
                // tiny public endpoints called on a timer by every open app: never count them
                || "/api/app/version".equals(p) || "/api/app/android-update".equals(p);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req, @NonNull HttpServletResponse res,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String ip = req.getRemoteAddr();
        if (ipCheckLogs.get() > 0 && ipCheckLogs.getAndDecrement() > 0) {
            String fwd = req.getHeader("X-Forwarded-For");
            log.info("Rate limiter client-IP check: sees {} (X-Forwarded-For header: {}). If every request "
                    + "shows the same internal/proxy address, the proxy setup is wrong and all users share "
                    + "one limit.", ip, fwd == null ? "none" : fwd);
        }
        String user = currentUser();
        String path = req.getRequestURI();
        String method = req.getMethod();

        for (Rule r : RULES) {
            if (r.method().equals(method) && path.startsWith(r.prefix())) {
                String who = r.perUser() && user != null ? "u:" + user : "ip:" + ip;
                long retry = hit(r.id() + "|" + who, r.max(), r.windowSec());
                if (retry > 0) { reject(res, retry); return; }
            }
        }
        long retry = hit("global|" + (user != null ? "u:" + user : "ip:" + ip), GLOBAL_MAX_PER_MIN, 60);
        if (retry > 0) { reject(res, retry); return; }
        chain.doFilter(req, res);
    }

    /** @return 0 if allowed, otherwise seconds until the window resets. */
    private long hit(String key, int max, long windowSec) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            if (now - w.start >= windowSec * 1000L) { w.start = now; w.count = 0; }
            w.count++;
            if (w.count > max) return Math.max(1, (w.start + windowSec * 1000L - now) / 1000L);
        }
        return 0;
    }

    private String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getPrincipal() instanceof String s && !"anonymousUser".equals(s) ? s : null;
    }

    private void reject(HttpServletResponse res, long retryAfter) throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/json");
        res.getWriter().write("{\"message\":\"Too many requests. Please slow down and try again shortly.\"}");
    }

    /** Drop expired counters so the map cannot grow without bound. */
    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().start > 3_700_000L);
        if (windows.size() > 200_000) windows.clear(); // emergency brake against key-flooding
    }
}