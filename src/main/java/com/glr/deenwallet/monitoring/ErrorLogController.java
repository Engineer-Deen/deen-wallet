package com.glr.deenwallet.monitoring;

import com.glr.deenwallet.config.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public endpoint (no login required) so errors can be captured even
 * on pages before the user logs in. If a valid JWT is present in the
 * Authorization header, the error is tagged with that user's ID;
 * otherwise it's stored anonymously. Nothing here triggers on its
 * own, the frontend only calls this when an actual error or
 * rage-click happens.
 *
 * SECURITY: Because this endpoint takes free-text fields from anyone
 * on the internet with no authentication, it is rate-limited per IP
 * below. This is not optional hardening - every field stored here can
 * later be displayed in the admin dashboard, so treat it the same as
 * any other public, untrusted input surface.
 */
@RestController
@RequestMapping("/api/errors")
@RequiredArgsConstructor
public class ErrorLogController {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_ACTION_BUFFER_LENGTH = 4000;
    private static final int MAX_REQUESTS_PER_WINDOW = 20;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final ErrorLogRepository errorLogRepository;
    private final JwtService jwtService;
    private final Map<String, RateState> rateStates = new ConcurrentHashMap<>();

    // SECURITY: Only set this to true if this app is deployed behind a
    // reverse proxy/load balancer that you control and that overwrites (not
    // appends to) X-Forwarded-For before forwarding the request. Otherwise a
    // client can send a different fake X-Forwarded-For value on every
    // request and completely bypass this rate limit. Left false, the limiter
    // uses the actual socket address, which cannot be spoofed.
    @org.springframework.beans.factory.annotation.Value("${app.errors.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    @PostMapping
    public ResponseEntity<Void> report(
            @Valid @RequestBody ErrorReportRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest httpRequest
    ) {
        if (isRateLimited(clientKey(httpRequest))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        ErrorLog errorLog = ErrorLog.builder()
                .userId(extractUserId(authHeader))
                .errorType(request.getErrorType())
                .message(truncate(request.getMessage(), MAX_MESSAGE_LENGTH))
                .statusCode(request.getStatusCode())
                .url(request.getUrl())
                // Always "user", regardless of what the request body claims. This is the
                // PUBLIC, unauthenticated endpoint - a client-supplied "admin" value here
                // was previously trusted, letting anyone inject fake admin-app errors into
                // the admin dashboard. Only the authenticated /api/admin/errors endpoint
                // (AdminController) is allowed to record an "admin" source.
                .sourceApp("user")
                .endpointPath(truncate(request.getEndpointPath(), 300))
                .httpMethod(truncate(request.getHttpMethod(), 10))
                .userAgent(userAgent)
                .actionBuffer(truncate(joinBuffer(request.getActionBuffer()), MAX_ACTION_BUFFER_LENGTH))
                .stack(truncate(request.getStack(), 10000))
                .line(request.getLine())
                .col(request.getCol())
                .build();

        errorLogRepository.save(errorLog);
        return ResponseEntity.noContent().build();
    }

    // Anything that isn't explicitly "admin" is treated as "user" - if this ever fails
    // to identify the app, the error still lands in the higher-traffic (user) bucket
    // rather than silently going unfiltered/uncounted.
    private UUID extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        if (!jwtService.isValid(token)) {
            return null;
        }
        try {
            return UUID.fromString(jwtService.extractUserId(token));
        } catch (Exception e) {
            return null;
        }
    }

    private String joinBuffer(List<String> actionBuffer) {
        if (actionBuffer == null || actionBuffer.isEmpty()) {
            return null;
        }
        return String.join(" | ", actionBuffer);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String clientKey(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private boolean isRateLimited(String key) {
        Instant now = Instant.now();
        RateState state = rateStates.computeIfAbsent(key, k -> new RateState());
        synchronized (state) {
            if (state.windowStart == null || Duration.between(state.windowStart, now).compareTo(WINDOW) > 0) {
                state.windowStart = now;
                state.count = 0;
            }
            state.count++;
            return state.count > MAX_REQUESTS_PER_WINDOW;
        }
    }

    private static final class RateState {
        Instant windowStart;
        int count;
    }
}

