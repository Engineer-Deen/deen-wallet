package com.glr.deenwallet.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final BiometricService biometricService;

    @PostMapping("/register")
    public ResponseEntity<Void> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        authService.register(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/confirm-email")
    public ResponseEntity<Void> confirmEmail(
            @Valid @RequestBody ConfirmEmailRequest r
    ) {
        authService.confirmEmail(r.email, r.code);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<Void> resendOtp(
            @Valid @RequestBody ResendOtpRequest r
    ) {
        authService.resendOtp(r.email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest r
    ) {
        return ResponseEntity.ok(authService.login(r));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest r
    ) {
        return ResponseEntity.ok(authService.refreshToken(r.refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) RefreshTokenRequest r
    ) {
        if (r != null) {
            authService.logout(r.refreshToken);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts the password-reset flow for a USER account.
     *
     * The service intentionally handles the email lookup without exposing
     * whether a particular email is registered.
     */

    @PostMapping("/biometric/registration-challenge")
    public ResponseEntity<BiometricService.ChallengeResponse> biometricRegistrationChallenge(
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(
                biometricService.createRegistrationChallenge(
                        UUID.fromString(authentication.getName())
                )
        );
    }

    @PostMapping("/biometric/register")
    public ResponseEntity<BiometricService.RegistrationResponse> biometricRegister(
            @RequestBody BiometricService.RegistrationRequest request,
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(
                biometricService.register(
                        UUID.fromString(authentication.getName()),
                        request
                )
        );
    }

    @PostMapping("/biometric/challenge")
    public ResponseEntity<BiometricService.ChallengeResponse> biometricChallenge(
            @RequestBody BiometricService.LoginChallengeRequest request) {
        if (request.credentialId() == null || request.credentialId().isBlank()) {
            throw new IllegalArgumentException("credentialId is required");
        }
        return ResponseEntity.ok(
                biometricService.createLoginChallenge(request)
        );
    }

    @PostMapping("/biometric/login")
    public ResponseEntity<LoginResponse> biometricLogin(
            @RequestBody BiometricService.AuthenticateRequest request) {
        return ResponseEntity.ok(biometricService.authenticate(request));
    }

    @GetMapping("/biometric/credentials")
    public ResponseEntity<java.util.List<BiometricService.CredentialResponse>> biometricCredentials(
            org.springframework.security.core.Authentication authentication) {
        return ResponseEntity.ok(
                biometricService.list(UUID.fromString(authentication.getName()))
        );
    }

    @DeleteMapping("/biometric/credentials/{id}")
    public ResponseEntity<Void> revokeBiometricCredential(
            @PathVariable UUID id,
            org.springframework.security.core.Authentication authentication) {
        biometricService.revoke(
                UUID.fromString(authentication.getName()),
                id
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest r
    ) {
        authService.requestPasswordReset(r.email);
        return ResponseEntity.noContent().build();
    }

    /**
     * Completes a password reset using the single-use reset token.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest r
    ) {
        authService.resetPassword(r.token, r.newPassword);
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts the separate Forgot-PIN recovery flow.
     * The response is intentionally identical for registered and
     * non-registered addresses.
     */
    @PostMapping("/forgot-pin")
    public ResponseEntity<Void> forgotPin(
            @Valid @RequestBody ForgotPinRequest r
    ) {
        authService.requestPinReset(r.email);
        return ResponseEntity.noContent().build();
    }

    /**
     * Verifies the 6-digit PIN recovery code and returns a short-lived,
     * one-time recovery authorization. No login tokens are issued.
     */
    @PostMapping("/verify-pin-reset-code")
    public ResponseEntity<VerifyPinResetCodeResponse> verifyPinResetCode(
            @Valid @RequestBody VerifyPinResetCodeRequest r
    ) {
        String resetToken = authService.verifyPinResetCode(r.email, r.code);
        return ResponseEntity.ok(new VerifyPinResetCodeResponse(resetToken));
    }

    /**
     * Completes the Forgot-PIN flow using the dedicated recovery authorization.
     */
    @PostMapping("/reset-pin")
    public ResponseEntity<Void> resetPin(
            @Valid @RequestBody ResetPinRequest r
    ) {
        authService.resetPin(r.token, r.newPin);
        return ResponseEntity.noContent().build();
    }

    @Getter
    @Setter
    public static class ConfirmEmailRequest {
        @NotBlank
        @Email
        private String email;

        @NotBlank
        private String code;
    }

    @Getter
    @Setter
    public static class ResendOtpRequest {
        @NotBlank
        @Email
        private String email;
    }

    @Getter
    @Setter
    public static class RefreshTokenRequest {
        @NotBlank
        private String refreshToken;
    }

    @Getter
    @Setter
    public static class ForgotPasswordRequest {
        @NotBlank
        @Email
        private String email;
    }

    @Getter
    @Setter
    public static class ForgotPinRequest {
        @NotBlank
        @Email
        private String email;
    }

    @Getter
    @Setter
    public static class VerifyPinResetCodeRequest {
        @NotBlank
        @Email
        private String email;

        @NotBlank
        private String code;
    }

    @Getter
    @Setter
    public static class ResetPinRequest {
        @NotBlank
        private String token;

        @NotBlank
        private String newPin;
    }

    @Getter
    @AllArgsConstructor
    public static class VerifyPinResetCodeResponse {
        private String resetToken;
    }

    @Getter
    @Setter
    public static class ResetPasswordRequest {
        @NotBlank
        private String token;

        @NotBlank
        private String newPassword;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private String accessToken;
        private String refreshToken;
        private String firstName;
        private String accountNumber;
        private String role;
    }
}