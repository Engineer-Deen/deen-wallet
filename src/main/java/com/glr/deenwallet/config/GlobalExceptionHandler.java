package com.glr.deenwallet.config;

import com.glr.deenwallet.admin.AdminAuthController;
import com.glr.deenwallet.auth.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Without this handler, every business-logic failure thrown by a service
 * (wrong password, locked account, rate limit, missing PIN, recovery
 * required, etc.) was an uncaught RuntimeException, so Spring's default
 * handler turned it into a bare 500 Internal Server Error with no body.
 * The frontend (see apiRequest() in deenwallet-client.js) already expects
 * a JSON body with a "message" field and correct status codes (400/401/
 * 403/409/423/429) - it was just never receiving them. This class is the
 * missing piece, not a change in behavior: it does not alter which
 * conditions are treated as errors, only how they're reported.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- Most specific first ----

    @ExceptionHandler(AuthService.UserLoginRateLimitedException.class)
    public ResponseEntity<Map<String, String>> handle(AuthService.UserLoginRateLimitedException ex) {
        HttpHeaders headers = new HttpHeaders();
        Instant lockedUntil = ex.getLockedUntil();
        if (lockedUntil != null) {
            long seconds = Math.max(0, Duration.between(Instant.now(), lockedUntil).getSeconds());
            headers.add("Retry-After", String.valueOf(seconds));
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(body(ex.getMessage()));
    }

    @ExceptionHandler({
            AuthService.AccountLockedException.class,
            AuthService.AccountPinLockedException.class,
            AdminAuthController.AdminLockedException.class,
            AdminAuthController.AdminRecoveryRequiredException.class
    })
    public ResponseEntity<Map<String, String>> handleLocked(RuntimeException ex) {
        // admin.html's login handler specifically checks `error.status === 423`
        // to decide whether to reveal the "Send recovery code" / OTP panel.
        // AdminRecoveryRequiredException must return 423 for that panel to
        // ever appear - it previously fell into the generic 403 handler
        // below, so the backend was correctly rejecting the login but the
        // frontend had no way to tell the user what to do about it.
        return ResponseEntity.status(HttpStatus.LOCKED).body(body(ex.getMessage()));
    }

    @ExceptionHandler(AdminAuthController.AdminEmailVerificationRequiredException.class)
    public ResponseEntity<Map<String, String>> handle(AdminAuthController.AdminEmailVerificationRequiredException ex) {
        // admin.html checks the X-Recovery-Required response header (not the
        // status code) to distinguish "email not verified" from "OTP
        // recovery needed" and show the right instructions in the same
        // panel. Without this header the panel never appeared for this case
        // either, for the same reason as AdminRecoveryRequiredException above.
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Recovery-Required", "EMAIL_VERIFICATION");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).headers(headers).body(body(ex.getMessage()));
    }

    @ExceptionHandler({
            AuthService.EmailVerificationRequiredException.class,
            AdminAuthController.AdminPinDisabledException.class
    })
    public ResponseEntity<Map<String, String>> handleVerificationRequired(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(ex.getMessage()));
    }

    @ExceptionHandler(AuthService.PinNotSetException.class)
    public ResponseEntity<Map<String, String>> handle(AuthService.PinNotSetException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handle(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body(ex.getMessage()));
    }

    // ---- Validation ----

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handle(NoResourceFoundException ex) {
        // Routine (favicon.ico, etc.) - not worth a full stack trace every time.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handle(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("Invalid request.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(message));
    }

    // ---- Generic fallbacks (must stay below the specific handlers above) ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handle(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handle(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(ex.getMessage()));
    }

    // ---- Anything else: log the real cause server-side, never leak it to the client ----

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        // The client (browser, mobile app, tunnel) disconnected before the
        // response finished - a tab closed, a page navigated away, a brief
        // network hiccup. This is routine, not a server error. Deliberately
        // don't attempt to write any response here: the connection is
        // already gone, and trying anyway is what previously caused a
        // second, more alarming-looking exception right after this one.
        log.debug("Client disconnected before response completed: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("Something went wrong. Please try again."));
    }

    private Map<String, String> body(String message) {
        return Map.of("message", message == null || message.isBlank() ? "Something went wrong." : message);
    }
}