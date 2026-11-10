package com.glr.deenwallet.admin;

import com.glr.deenwallet.auth.RefreshToken;
import com.glr.deenwallet.auth.RefreshTokenRepository;
import com.glr.deenwallet.config.JwtService;
import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.otp.OtpService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private static final int MAX_ADMIN_PASSWORD_FAILURES = 2;

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenRepository refreshTokens;
    private final OtpService otpService;
    private final EmailService emailService;
    private final AdminPasscodeService passcodes;
    private final AdminPasskeyService passkeys;
    private final com.glr.deenwallet.auth.TurnstileService turnstile;

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(
            @Valid @RequestBody AdminLoginRequest r,
            jakarta.servlet.http.HttpServletRequest http
    ) {
        turnstile.verify(r.getTurnstileToken());
        String email = r.email.trim().toLowerCase(Locale.ROOT);

        String ip = http.getRemoteAddr();
        Instant paused = adminGuard.pausedUntil(email, ip, Instant.now());
        if (paused != null) {
            throw new AdminLockedException(adminPauseMessage(paused));
        }

        /*
         USER and ADMIN/SUPER_ADMIN accounts may share the same email.
         Therefore, admin authentication must explicitly search only
         administrator roles.
         SUPER_ADMIN is checked first so that an administrator email
         cannot accidentally resolve to a normal USER account.
         */
        User u = findAdminByEmailOrNull(email);
        if (u == null) {
            com.glr.deenwallet.monitoring.SecurityTag.tag("LOGIN_UNKNOWN", email);
            burnPasswordTime(r.password);
            throw adminFailure(null, email, ip, Instant.now());
        }

        // The password is checked FIRST. Only someone who knows it is told about the state of the account
        // (inactive / unverified / disabled), so those messages cannot be used to find out who is an administrator.
        if (!encoder.matches(r.password, u.getPasswordHash())) {
            com.glr.deenwallet.monitoring.SecurityTag.tag("LOGIN_FAILED", email);
            throw adminFailure(u, email, ip, Instant.now());
        }
        adminGuard.success(email, ip);

        if (!u.isActive()) {
            throw new IllegalStateException(
                    "Admin account is inactive. Please contact super admin."
            );
        }

        if (!u.isEmailVerified()) {
            throw new AdminEmailVerificationRequiredException(
                    "Please verify your admin email before logging in. Request the verification code below."
            );
        }

        if (u.isLocked() && !("SUPER_ADMIN".equals(u.getRole()) && u.isMainAdmin())) {
            throw new AdminLockedException(
                    "Admin account is disabled after multiple incorrect password attempts. Request an OTP to restore access or contact the super admin."
            );
        }

        if ("SUPER_ADMIN".equals(u.getRole())
                && u.isAdminRecoveryRequired()) {

            throw new AdminRecoveryRequiredException(
                    "Additional verification is required. Request the OTP sent to your admin email to continue."
            );
        }

        repo.clearLoginFailures(u.getId());

        u.setPinAttempts(0);
        repo.save(u);

        com.glr.deenwallet.monitoring.SecurityTag.good();
        return ResponseEntity.ok(issue(u));
    }

    @PostMapping("/recovery/send-otp")
    public ResponseEntity<RecoveryResponse> sendRecoveryOtp(
            @Valid @RequestBody RecoveryRequest r
    ) {
        String email = r.email.trim().toLowerCase(Locale.ROOT);

        User user = findAdminByEmailOrNull(email);

        if (user != null) {
            otpService.generateAndSend(email);
        }

        return ResponseEntity.ok(
                new RecoveryResponse(
                        "If the address belongs to an administrator, a recovery code has been sent."
                )
        );
    }

    @PostMapping("/recovery/verify-otp")
    public ResponseEntity<AdminLoginResponse> verifyRecoveryOtp(
            @Valid @RequestBody RecoveryOtpRequest r
    ) {
        String email = r.email.trim().toLowerCase(Locale.ROOT);
        User user = findAdminByEmail(email);

        if (!otpService.verify(email, r.code.trim())) {
            throw new IllegalArgumentException(
                    "Invalid or expired recovery code"
            );
        }

        user.setAdminRecoveryRequired(false);
        user.setEmailVerified(true);
        user.setLoginFailedAttempts(0);
        user.setLoginLockedUntil(null);
        user.setLastLoginFailedAt(null);
        user.setLocked(false);
        user.setLockedAt(null);
        user.setLockedBy(null);
        user.setActive(true);
        user.setPinAttempts(0);

        repo.save(user);

        log.info("Admin recovery completed for {}", email);

        return ResponseEntity.ok(issue(user));
    }

    /** Second recovery method: one of the admin's saved passcodes (works even when email is down). */
    @PostMapping("/recovery/verify-passcode")
    public ResponseEntity<AdminLoginResponse> verifyRecoveryPasscode(
            @Valid @RequestBody RecoveryPasscodeRequest r
    ) {
        String email = r.email.trim().toLowerCase(Locale.ROOT);
        User user = findAdminByEmailOrNull(email);

        if (!passcodes.verifyForRecovery(email, user == null ? null : user.getId(), r.passcode)) {
            throw new IllegalArgumentException(passcodes.badMessage());
        }

        user.setAdminRecoveryRequired(false);
        user.setLoginFailedAttempts(0);
        user.setLoginLockedUntil(null);
        user.setLastLoginFailedAt(null);
        user.setLocked(false);
        user.setLockedAt(null);
        user.setLockedBy(null);
        user.setPinAttempts(0);

        repo.save(user);

        log.info("Admin recovery by passcode completed for {}", email);

        return ResponseEntity.ok(issue(user));
    }

    /** Third recovery method: a passkey (the phone's screen lock). Step 1: a one-time challenge. */
    @PostMapping("/recovery/passkey-options")
    public ResponseEntity<Map<String, Object>> passkeyOptions(@RequestBody(required = false) PasskeyOptionsRequest r) {
        User user = null;
        if (r != null && r.email != null && !r.email.isBlank()) {
            user = findAdminByEmailOrNull(r.email.trim().toLowerCase(Locale.ROOT));
        }
        return ResponseEntity.ok(passkeys.loginOptions(user));
    }

    /** Step 2: the signed answer. A valid signature restores access exactly like the email code does. */
    @PostMapping("/recovery/verify-passkey")
    public ResponseEntity<AdminLoginResponse> verifyRecoveryPasskey(
            @Valid @RequestBody PasskeyVerifyRequest r
    ) {
        UUID adminId = passkeys.verifyLogin(r.challengeId, r.id, r.clientDataJSON, r.authenticatorData, r.signature);
        User user = repo.findById(adminId)
                .filter(this::isAdmin)
                .orElseThrow(() -> new IllegalArgumentException("Passkey check failed. Try again, or use Email or a Passcode."));

        user.setAdminRecoveryRequired(false);
        user.setLoginFailedAttempts(0);
        user.setLoginLockedUntil(null);
        user.setLastLoginFailedAt(null);
        user.setLocked(false);
        user.setLockedAt(null);
        user.setLockedBy(null);
        user.setPinAttempts(0);

        repo.save(user);

        log.info("Admin recovery by passkey completed for {}", user.getEmail());

        return ResponseEntity.ok(issue(user));
    }

    @Transactional
    @PostMapping("/refresh")
    public ResponseEntity<AdminLoginResponse> refresh(
            @Valid @RequestBody RefreshRequest r
    ) {
        if (!jwt.isValid(r.refreshToken)
                || !jwt.isRefreshToken(r.refreshToken)) {

            throw new IllegalArgumentException(
                    "Invalid or expired admin refresh token"
            );
        }

        String jti = jwt.extractJti(r.refreshToken);

        RefreshToken stored = refreshTokens.findByJti(jti)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Invalid or revoked admin refresh token"
                        ));

        if (stored.isRevoked()
                || stored.getExpiresAt().isBefore(Instant.now())) {

            throw new IllegalArgumentException(
                    "Invalid or expired admin refresh token"
            );
        }

        UUID userId = UUID.fromString(
                jwt.extractUserId(r.refreshToken)
        );

        User user = repo.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Admin account not found"
                        ));

        if (!isAdmin(user)) {
            throw new IllegalArgumentException(
                    "Invalid admin refresh token"
            );
        }

        if (!user.isActive() || user.isLocked()) {
            throw new AdminLockedException(
                    "Admin account is inactive or locked"
            );
        }

        if (user.getPinAttempts() >= 2) {
            throw new AdminPinDisabledException(
                    "Admin PIN is disabled for this session. Please log in again."
            );
        }

        if ("SUPER_ADMIN".equals(user.getRole())
                && user.isAdminRecoveryRequired()) {

            throw new AdminRecoveryRequiredException(
                    "Admin recovery is required before this session can continue."
            );
        }

        stored.setRevoked(true);
        refreshTokens.save(stored);

        return ResponseEntity.ok(issue(user));
    }

    @Getter
    @Setter
    public static class AdminLoginRequest {
        @NotBlank
        @Email
        String email;

        @NotBlank
        String password;

        String turnstileToken;
    }

    @Getter
    @Setter
    public static class RecoveryRequest {
        @NotBlank
        @Email
        String email;
    }

    @Getter
    @Setter
    public static class RecoveryOtpRequest {
        @NotBlank
        @Email
        String email;

        @NotBlank
        String code;
    }

    @Getter
    @Setter
    public static class RecoveryPasscodeRequest {
        @NotBlank
        @Email
        String email;

        @NotBlank
        String passcode;
    }

    @Getter
    @Setter
    public static class PasskeyOptionsRequest {
        String email;
    }

    @Getter
    @Setter
    public static class PasskeyVerifyRequest {
        @NotBlank
        String challengeId;

        @NotBlank
        String id;

        @NotBlank
        String clientDataJSON;

        @NotBlank
        String authenticatorData;

        @NotBlank
        String signature;
    }

    @Getter
    @Setter
    public static class RefreshRequest {
        @NotBlank
        String refreshToken;
    }

    public record RecoveryResponse(String message) {}

    public record AdminLoginResponse(
            String accessToken,
            String refreshToken,
            String firstName,
            String accountNumber,
            String role
    ) {}


    private final AdminLoginGuard adminGuard = new AdminLoginGuard();

    private static String adminPauseMessage(Instant until) {
        return "Too many incorrect attempts. Admin sign-in is temporarily paused until " + until
                + ". Please try again later, or use the restore-access options.";
    }

    /** The reply to a wrong password or an unknown email: the same words, and the same pauses, for both. */
    private RuntimeException adminFailure(User admin, String email, String ip, Instant now) {
        AdminLoginGuard.Outcome o = adminGuard.fail(email, ip, now);
        if (o.accountJustPaused() && admin != null) {
            try {
                emailService.sendAdminLoginBlockedEmail(admin, "SUPER_ADMIN".equals(admin.getRole()));
            } catch (RuntimeException ignored) {
                // the notice is best effort; the pause already protects the account
            }
        }
        if (o.until() != null) {
            return new AdminLockedException(adminPauseMessage(o.until()));
        }
        return new AdminPasswordException("Invalid admin credentials");
    }

    private volatile String dummyHash;

    /** Spends the same time a real password check would, so the reply time does not give an unknown email away. */
    private void burnPasswordTime(String password) {
        try {
            String h = dummyHash;
            if (h == null) {
                h = encoder.encode("dw-unused-" + UUID.randomUUID());
                dummyHash = h;
            }
            encoder.matches(password == null ? "" : password, h);
        } catch (RuntimeException ignored) {
            // timing hint only
        }
    }

    private User findAdminByEmail(String email) {
        return repo.findByEmailAndRole(email, "SUPER_ADMIN")
                .or(() -> repo.findByEmailAndRole(email, "ADMIN"))
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Invalid admin credentials"
                        ));
    }

    private User findAdminByEmailOrNull(String email) {
        return repo.findByEmailAndRole(email, "SUPER_ADMIN")
                .or(() -> repo.findByEmailAndRole(email, "ADMIN"))
                .orElse(null);
    }

    private boolean isAdmin(User u) {
        return "ADMIN".equals(u.getRole())
                || "SUPER_ADMIN".equals(u.getRole());
    }

    private AdminLoginResponse issue(User u) {
        String role = u.getRole();

        String access = jwt.generateAccessToken(
                u.getId().toString(),
                role
        );

        String refresh = jwt.generateRefreshToken(
                u.getId().toString(),
                role
        );

        refreshTokens.save(
                RefreshToken.builder()
                        .jti(jwt.extractJti(refresh))
                        .userId(u.getId())
                        .expiresAt(
                                jwt.extractExpiration(refresh)
                                        .toInstant()
                        )
                        .revoked(false)
                        .build()
        );

        return new AdminLoginResponse(
                access,
                refresh,
                u.getFirstName(),
                u.getAccountNumber(),
                role
        );
    }

    public static class AdminPasswordException
            extends IllegalArgumentException {

        public AdminPasswordException(String message) {
            super(message);
        }
    }

    public static class AdminLockedException
            extends IllegalStateException {

        public AdminLockedException(String message) {
            super(message);
        }
    }

    public static class AdminRecoveryRequiredException
            extends IllegalStateException {

        public AdminRecoveryRequiredException(String message) {
            super(message);
        }
    }

    public static class AdminEmailVerificationRequiredException
            extends IllegalStateException {

        public AdminEmailVerificationRequiredException(String message) {
            super(message);
        }
    }

    public static class AdminPinDisabledException
            extends IllegalStateException {

        public AdminPinDisabledException(String message) {
            super(message);
        }
    }
}