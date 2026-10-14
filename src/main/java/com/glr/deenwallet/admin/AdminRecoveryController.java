package com.glr.deenwallet.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints (an admin who forgot a credential is not signed in). They live under /api/admin/auth/**,
 * which SecurityConfig already permits, and are throttled by RateLimitFilter.
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminRecoveryController {

    private final AdminRecoveryService recovery;

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody EmailRequest r) {
        recovery.requestPasswordReset(r.email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest r) {
        recovery.resetPassword(r.token, r.newPassword);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-pin")
    public ResponseEntity<Void> forgotPin(@Valid @RequestBody EmailRequest r) {
        recovery.requestPinReset(r.email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-pin-reset-code")
    public ResponseEntity<VerifyPinResetCodeResponse> verifyPinResetCode(@Valid @RequestBody VerifyCodeRequest r) {
        return ResponseEntity.ok(new VerifyPinResetCodeResponse(recovery.verifyPinResetCode(r.email, r.code)));
    }

    @PostMapping("/reset-pin")
    public ResponseEntity<Void> resetPin(@Valid @RequestBody ResetPinRequest r) {
        recovery.resetPin(r.token, r.newPin);
        return ResponseEntity.noContent().build();
    }

    @Getter @Setter
    public static class EmailRequest {
        @NotBlank @Email String email;
    }

    @Getter @Setter
    public static class ResetPasswordRequest {
        @NotBlank String token;
        @NotBlank String newPassword;
    }

    @Getter @Setter
    public static class VerifyCodeRequest {
        @NotBlank @Email String email;
        @NotBlank String code;
    }

    @Getter @Setter
    public static class ResetPinRequest {
        @NotBlank String token;
        @NotBlank String newPin;
    }

    /** Field is named resetToken: the reset-pin page reads data.resetToken, exactly like the customer page. */
    public record VerifyPinResetCodeResponse(String resetToken) {}
}
