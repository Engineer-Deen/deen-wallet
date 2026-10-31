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

    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(
            @Valid @RequestBody AdminLoginRequest r
    ) {
        String email = r.email.trim().toLowerCase(Locale.ROOT);

        /*
         USER and ADMIN/SUPER_ADMIN accounts may share the same email.
         Therefore, admin authentication must explicitly search only
         administrator roles.
         SUPER_ADMIN is checked first so that an administrator email
         cannot accidentally resolve to a normal USER account.
         */
        User u = findAdminByEmail(email);

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

        if (!encoder.matches(r.password, u.getPasswordHash())) {

            Instant now = Instant.now();

            int changed = repo.recordAdminPasswordFailure(
                    u.getId(),
                    now
            );

            User state = repo.findById(u.getId()).orElse(u);

            int failures = state.getLoginFailedAttempts();

            if (failures >= MAX_ADMIN_PASSWORD_FAILURES) {

                if (changed == 1
                        && failures == MAX_ADMIN_PASSWORD_FAILURES) {

                    if ("SUPER_ADMIN".equals(state.getRole())) {
                        state.setAdminRecoveryRequired(true);
                        repo.save(state);

                        emailService.sendAdminLoginBlockedEmail(
                                state,
                                true
                        );

                        throw new AdminRecoveryRequiredException(
                                "Two incorrect password attempts were detected. Your super admin account was not locked. Request the email OTP to verify ownership and continue."
                        );
                    }

                    state.setLocked(true);
                    state.setLockedAt(now);

                    repo.save(state);

                    emailService.sendAdminLoginBlockedEmail(
                            state,
                            false
                    );

                    throw new AdminLockedException(
                            "Admin account disabled after 2 incorrect password attempts. Request an OTP to restore access or contact the super admin."
                    );
                }

                if ("SUPER_ADMIN".equals(state.getRole())) {
                    throw new AdminRecoveryRequiredException(
                            "Additional verification is required. Request the recovery OTP to continue."
                    );
                }

                throw new AdminLockedException(
                        "Admin account disabled after 2 incorrect password attempts. Request an OTP to restore access or contact the super admin."
                );
            }

            int remaining =
                    MAX_ADMIN_PASSWORD_FAILURES - failures;

            throw new AdminPasswordException(
                    "Incorrect admin password. "
                            + remaining
                            + " warning(s) remaining before admin protection is activated."
            );
        }

        repo.clearLoginFailures(u.getId());

        u.setPinAttempts(0);
        repo.save(u);

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
        user.setActive(true);
        user.setPinAttempts(0);

        repo.save(user);

        log.info("Admin recovery by passcode completed for {}", email);

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