
package com.glr.deenwallet.config;

import com.glr.deenwallet.admin.AdminAuthController;
import com.glr.deenwallet.auth.AuthService;
import com.glr.deenwallet.transaction.BankKycUnavailableException;
import com.glr.deenwallet.transaction.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Handles application exceptions and returns consistent JSON error responses.
 *
 * Business errors retain their intended HTTP status codes so that frontend
 * and mobile clients can respond correctly to authentication failures,
 * locked accounts, validation errors, rate limits, and other expected errors.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- Authentication and account status ----

    @ExceptionHandler(AuthService.UserLoginRateLimitedException.class)
    public ResponseEntity<Map<String, String>> handle(
            AuthService.UserLoginRateLimitedException ex) {

        HttpHeaders headers = new HttpHeaders();
        Instant lockedUntil = ex.getLockedUntil();

        if (lockedUntil != null) {
            long seconds = Math.max(
                    0,
                    Duration.between(Instant.now(), lockedUntil).getSeconds()
            );
            headers.add("Retry-After", String.valueOf(seconds));
        }

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body("Invalid request body."));
    }

    @ExceptionHandler({
            AuthService.AccountLockedException.class,
            AuthService.AccountPinLockedException.class,
            AdminAuthController.AdminLockedException.class,
            AdminAuthController.AdminRecoveryRequiredException.class
    })
    public ResponseEntity<Map<String, String>> handleLocked(
            RuntimeException ex) {

        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler(
            AdminAuthController.AdminEmailVerificationRequiredException.class
    )
    public ResponseEntity<Map<String, String>> handle(
            AdminAuthController.AdminEmailVerificationRequiredException ex) {

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Recovery-Required", "EMAIL_VERIFICATION");

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .headers(headers)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler({
            AuthService.EmailVerificationRequiredException.class,
            AdminAuthController.AdminPinDisabledException.class
    })
    public ResponseEntity<Map<String, String>> handleVerificationRequired(
            RuntimeException ex) {

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler(AuthService.PinNotSetException.class)
    public ResponseEntity<Map<String, String>> handle(
            AuthService.PinNotSetException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> handle(
            SecurityException ex) {

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(body(ex.getMessage()));
    }

    // ---- Explicit HTTP status exceptions ----

    /**
     * Preserve status codes deliberately thrown by controllers and services.
     *
     * For example, ResponseStatusException(HttpStatus.UNAUTHORIZED, ...)
     * must return 401 rather than falling through to the generic 500 handler.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(
            ResponseStatusException ex) {

        HttpHeaders headers = new HttpHeaders();
        headers.putAll(ex.getHeaders());

        return ResponseEntity.status(ex.getStatusCode())
                .headers(headers)
                .body(body(ex.getReason()));
    }

    // ---- Validation ----

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handle(NoResourceFoundException ex) {

        // Routine missing resources such as favicon.ico do not need a stack trace.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handle(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("Invalid request.");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(message));
    }

    @ExceptionHandler(BankKycUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleBankKycUnavailable(
            BankKycUnavailableException ex) {

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body(ex.getMessage()));
    }

    // ---- Generic application errors ----

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handle(
            HttpRequestMethodNotSupportedException ex) {

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body("HTTP method not allowed for this endpoint."));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, String>> handle(
            TooManyRequestsException ex) {

        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "30");

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handle(
            IllegalArgumentException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handle(
            IllegalStateException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body(ex.getMessage()));
    }

    // ---- Client disconnects ----

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {

        // The client disconnected before the response completed.
        log.debug(
                "Client disconnected before response completed: {}",
                ex.getMessage()
        );
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(
            AsyncRequestNotUsableException ex) {

        // Spring may wrap a client disconnect in this exception.
        log.debug(
                "Client disconnected before response completed: {}",
                ex.getMessage()
        );
    }

    // ---- Upstream service errors ----

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(
            RestClientResponseException ex) {

        log.error(
                "Upstream call failed: status={} body={}",
                ex.getStatusCode(),
                ex.getResponseBodyAsString()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body(
                        "The payment provider could not complete this request. Please try again."
                ));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> handleUpstreamTimeout(
            ResourceAccessException ex) {

        log.error("Upstream call timed out or was unreachable", ex);

        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(body(
                        "The payment provider is taking too long to respond. Please try again."
                ));
    }

    // ---- Final fallback ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception ex) {

        log.error("Unhandled exception", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("Something went wrong. Please try again."));
    }

    // ---- JSON response helper ----

    private Map<String, String> body(String message) {

        return Map.of(
                "message",
                message == null || message.isBlank()
                        ? "Something went wrong."
                        : message
        );
    }
}
