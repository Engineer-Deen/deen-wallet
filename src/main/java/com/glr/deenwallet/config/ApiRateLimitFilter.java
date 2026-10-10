package com.glr.deenwallet.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-level API abuse protection.
 *
 * This filter is deliberately separate from financial idempotency:
 *
 * - Rate limiting controls request volume.
 * - Idempotency prevents duplicate financial operations.
 *
 * Monime webhooks are excluded because Monime must be able to retry
 * webhook deliveries without being blocked by a client-facing rate limit.
 *
 * This is application-level protection. A production deployment should
 * still have a reverse proxy / WAF / load balancer providing distributed
 * DDoS protection in front of the application.
 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration CLEANUP_AGE = Duration.ofMinutes(10);

    /**
     * Maximum requests from one authenticated user per minute.
     */
    private static final int AUTHENTICATED_REQUESTS_PER_MINUTE = 120;

    /**
     * Maximum requests from one unauthenticated client IP per minute.
     */
    private static final int PUBLIC_REQUESTS_PER_MINUTE = 60;

    /**
     * Financial transaction creation is deliberately much stricter.
     *
     * This protects:
     * - POST /api/transactions
     * - POST /api/bank-transfers
     *
     * A user can still perform legitimate transfers, but cannot rapidly
     * spam the financial creation endpoints.
     */
    private static final int FINANCIAL_CREATION_REQUESTS_PER_MINUTE = 6;

    /**
     * Provider/account lookup endpoints can cause external Monime calls,
     * so they receive their own lower limit.
     */
    private static final int LOOKUP_REQUESTS_PER_MINUTE = 60;

    /**
     * Public authentication endpoints such as login, register, OTP,
     * refresh and similar endpoints need a stricter limit than ordinary
     * public API traffic.
     */
    private static final int AUTH_PUBLIC_REQUESTS_PER_MINUTE = 12;

    /**
     * In-memory counters.
     *
     * This protects a single application instance. For multiple backend
     * instances, a distributed limiter at the reverse proxy/WAF layer
     * should also be used.
     */
    private final Map<String, WindowCounter> counters =
            new ConcurrentHashMap<>();

    private final JwtService jwtService;

    @Value("${app.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    /**
     * Explicit constructor instead of relying on Lombok constructor
     * generation. This guarantees JwtService is initialized correctly
     * even if annotation processing is unavailable in the IDE/build.
     */
    public ApiRateLimitFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        /*
         * OPTIONS requests are normally browser CORS preflight requests.
         * They should not consume the user's API quota.
         */
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        /*
         * Monime webhooks must not be blocked by this client-facing
         * limiter. Webhook authenticity is handled by the webhook
         * signature verification layer.
         */
        if (isWebhook(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        /*
         * Static frontend resources do not need API rate limiting.
         */
        if (isStaticResource(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Identity identityInfo = identify(request);

        boolean authenticated = identityInfo.authenticated();
        String identity = identityInfo.key();

        /*
         * First apply the general request limit.
         */
        Limit globalLimit = authenticated
                ? new Limit(AUTHENTICATED_REQUESTS_PER_MINUTE)
                : new Limit(PUBLIC_REQUESTS_PER_MINUTE);

        if (!allow(identity + ":global", globalLimit)) {
            reject(response, globalLimit);
            return;
        }

        /*
         * Then apply endpoint-specific limits.
         */
        Limit endpointLimit =
                endpointLimit(request, authenticated);

        if (endpointLimit != null
                && !allow(
                identity + ":" + endpointBucket(request),
                endpointLimit
        )) {

            reject(response, endpointLimit);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Returns an endpoint-specific rate limit.
     */
    private Limit endpointLimit(
            HttpServletRequest request,
            boolean authenticated
    ) {

        String path = request.getRequestURI();
        String method = request.getMethod().toUpperCase();

        /*
         * Unauthenticated traffic:
         *
         * Authentication endpoints are more sensitive because they
         * can be abused for credential stuffing, OTP abuse, registration
         * abuse, password reset abuse, etc.
         */
        if (!authenticated) {

            if (path.startsWith("/api/auth/")
                    || path.startsWith("/api/admin/auth/")) {

                return new Limit(
                        AUTH_PUBLIC_REQUESTS_PER_MINUTE
                );
            }

            return null;
        }

        /*
         * Financial creation endpoints receive the strictest limit.
         */
        if ("POST".equals(method)
                && (
                "/api/transactions".equals(path)
                        || "/api/bank-transfers".equals(path)
        )) {

            return new Limit(
                    FINANCIAL_CREATION_REQUESTS_PER_MINUTE
            );
        }

        /*
         * Account/provider lookups can cause upstream Monime calls.
         */
        if ("/api/accounts/detect-provider".equals(path)
                || "/api/bank-transfers/verify-account".equals(path)) {

            return new Limit(
                    LOOKUP_REQUESTS_PER_MINUTE
            );
        }

        return null;
    }

    /**
     * Creates a stable bucket name for endpoint-specific limits.
     */
    private String endpointBucket(
            HttpServletRequest request
    ) {

        String path = request.getRequestURI();

        if ("/api/transactions".equals(path)
                || "/api/bank-transfers".equals(path)) {

            return "financial-create";
        }

        if ("/api/accounts/detect-provider".equals(path)
                || "/api/bank-transfers/verify-account".equals(path)) {

            return "account-lookup";
        }

        if (path.startsWith("/api/auth/")
                || path.startsWith("/api/admin/auth/")) {

            return "auth";
        }

        return "general";
    }

    /**
     * Attempts to consume one request from a rate-limit bucket.
     */
    private boolean allow(
            String key,
            Limit limit
    ) {

        WindowCounter counter =
                counters.computeIfAbsent(
                        key,
                        ignored -> new WindowCounter()
                );

        return counter.tryAcquire(
                limit.maxRequests(),
                WINDOW
        );
    }

    /**
     * Identifies an authenticated request by JWT user ID.
     *
     * If the token is invalid, the request is charged against its IP
     * rather than being trusted as an authenticated identity.
     *
     * JwtAuthFilter remains responsible for the authoritative
     * authentication decision.
     */
    private Identity identify(
            HttpServletRequest request
    ) {

        String authorization =
                request.getHeader("Authorization");

        if (authorization != null
                && authorization.startsWith("Bearer ")) {

            String token =
                    authorization.substring(7).trim();

            if (!token.isBlank()) {

                try {

                    if (jwtService.isAccessToken(token)) {

                        String userId =
                                jwtService.extractUserId(token);

                        if (userId != null
                                && !userId.isBlank()) {

                            return new Identity(
                                    "user:" + userId,
                                    true
                            );
                        }
                    }

                } catch (RuntimeException ignored) {

                    /*
                     * Invalid/malformed tokens are deliberately not
                     * allowed to establish a trusted identity.
                     *
                     * Charge the request to the client IP instead.
                     */
                }
            }
        }

        return new Identity(
                "ip:" + clientIp(request),
                false
        );
    }

    /**
     * Determines the client IP.
     *
     * X-Forwarded-For is only trusted when explicitly enabled through
     * configuration. This prevents an attacker from simply supplying
     * arbitrary X-Forwarded-For headers to bypass the limiter.
     */
    private String clientIp(
            HttpServletRequest request
    ) {

        if (trustForwardedFor) {

            String forwarded =
                    request.getHeader("X-Forwarded-For");

            if (forwarded != null
                    && !forwarded.isBlank()) {

                String first =
                        forwarded.split(",", 2)[0].trim();

                if (!first.isBlank()) {
                    return first;
                }
            }
        }

        return request.getRemoteAddr();
    }

    /**
     * Monime webhook endpoint.
     *
     * Webhook authenticity must be established by the Monime
     * signature verification logic, not by this client limiter.
     */
    private boolean isWebhook(
            HttpServletRequest request
    ) {

        return "/api/v1/webhooks/monime"
                .equals(request.getRequestURI());
    }

    /**
     * Everything outside /api/ is considered a static/frontend
     * resource and bypasses this API limiter.
     */
    private boolean isStaticResource(
            HttpServletRequest request
    ) {

        String path =
                request.getRequestURI();

        return !path.startsWith("/api/");
    }

    /**
     * Sends HTTP 429 Too Many Requests.
     *
     * 429 is used directly rather than relying on a servlet constant,
     * avoiding compatibility problems with servlet API versions.
     */
    private void reject(
            HttpServletResponse response,
            Limit limit
    ) throws IOException {

        response.setStatus(429);

        response.setContentType(
                "application/json"
        );

        response.setCharacterEncoding(
                "UTF-8"
        );

        response.setHeader(
                "Retry-After",
                "60"
        );

        response.getWriter().write(
                "{\"message\":\"Too many requests. Please wait and try again.\"}"
        );
    }

    /**
     * Removes inactive rate-limit buckets every five minutes.
     *
     * This prevents the in-memory map from growing forever as new
     * client IPs/user IDs appear.
     */
    @Scheduled(fixedRate = 300_000)
    public void cleanupExpiredBuckets() {

        Instant cutoff =
                Instant.now().minus(CLEANUP_AGE);

        counters.entrySet().removeIf(
                entry ->
                        entry.getValue()
                                .lastSeen()
                                .isBefore(cutoff)
        );
    }

    private record Identity(
            String key,
            boolean authenticated
    ) {
    }

    private record Limit(
            int maxRequests
    ) {
    }

    /**
     * One-minute sliding-window request counter.
     */
    private static final class WindowCounter {

        private final ArrayDeque<Long> timestamps =
                new ArrayDeque<>();

        private volatile Instant lastSeen =
                Instant.now();

        synchronized boolean tryAcquire(
                int maxRequests,
                Duration window
        ) {

            long now =
                    System.currentTimeMillis();

            long cutoff =
                    now - window.toMillis();

            /*
             * Remove requests that have fallen outside the
             * current sliding window.
             */
            while (!timestamps.isEmpty()
                    && timestamps.peekFirst() <= cutoff) {

                timestamps.removeFirst();
            }

            /*
             * The limit has been reached.
             */
            if (timestamps.size() >= maxRequests) {

                lastSeen =
                        Instant.now();

                return false;
            }

            /*
             * Record this request.
             */
            timestamps.addLast(now);

            lastSeen =
                    Instant.now();

            return true;
        }

        Instant lastSeen() {
            return lastSeen;
        }
    }
}