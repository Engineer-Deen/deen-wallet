package com.glr.deenwallet.admin;

import com.glr.deenwallet.auth.PasswordResetToken;
import com.glr.deenwallet.auth.PasswordResetTokenRepository;
import com.glr.deenwallet.auth.RefreshTokenRepository;
import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Forgot password" and "Forgot PIN" for ADMIN / SUPER_ADMIN accounts. It mirrors the customer app:
 *  - password: emailed one-time link (30 min) -> new password
 *  - PIN:      emailed 6-digit code (10 min, 5 tries) -> one-time authorization (10 min) -> new 4-digit PIN
 *
 * Safety rules specific to admins:
 *  - The answer to every "forgot" request is identical whether or not the email belongs to an admin.
 *  - Only ACTIVE admins with a VERIFIED email can recover; a deactivated admin stays deactivated.
 *  - A password reset clears the sign-in protection only when it was caused by wrong passwords. An admin that a
 *    super admin locked on purpose (no failed attempts) stays locked.
 *  - Every successful reset signs the admin out everywhere (refresh tokens revoked) and emails a security notice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRecoveryService {

    private static final Duration PASSWORD_TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final Duration PIN_OTP_LIFETIME = Duration.ofMinutes(10);
    private static final Duration PIN_TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final int PIN_OTP_MAX_ATTEMPTS = 5;
    private static final int MAX_REQUESTS_PER_WINDOW = 5;
    private static final Duration REQUEST_WINDOW = Duration.ofMinutes(15);
    private static final long REQUEST_COOLDOWN_SECONDS = 60;
    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 128;
    /** Same threshold as AdminAuthController.MAX_ADMIN_PASSWORD_FAILURES. */
    private static final int LOCKOUT_FAILURES = 2;

    private static final String INVALID_LINK = "Invalid or expired password reset token";
    private static final String INVALID_CODE = "Invalid or expired verification code";
    private static final String INVALID_PIN_AUTH = "Invalid or expired PIN recovery authorization";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AdminPinResetChallengeRepository pinChallenges;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailService emailService;

    private final SecureRandom random = new SecureRandom();
    /** Per-email request log. Keyed by the typed email whether or not it exists, so it leaks nothing. */
    private final Map<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ password

    @Transactional
    public void requestPasswordReset(String email) {
        String normalized = normalize(email);
        if (normalized.isBlank() || !allowRequest("pw", normalized)) return;

        User admin = findAdmin(normalized).orElse(null);
        if (!canRecover(admin)) return;

        Instant now = Instant.now();
        passwordResetTokenRepository.markAllUsedForUser(admin.getId(), now);   // only one live link per admin

        String rawToken = randomToken();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .userId(admin.getId())
                .tokenHash(hash(rawToken))
                .expiresAt(now.plus(PASSWORD_TOKEN_LIFETIME))
                .usedAt(null)
                .build());

        try {
            emailService.sendAdminPasswordResetEmail(admin, rawToken);
            log.info("Admin password reset link sent");
        } catch (RuntimeException e) {
            // Never reveal (through an error) that this address belongs to an admin.
            log.warn("Admin password reset email could not be sent: {}", e.toString());
        }
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) throw new IllegalArgumentException(INVALID_LINK);
        if (newPassword == null || newPassword.length() < PASSWORD_MIN || newPassword.length() > PASSWORD_MAX) {
            throw new IllegalArgumentException("Password must be between " + PASSWORD_MIN + " and " + PASSWORD_MAX + " characters");
        }

        PasswordResetToken token = passwordResetTokenRepository.findByTokenHash(hash(rawToken.trim()))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));
        if (token.isUsed() || token.isExpired()) throw new IllegalArgumentException(INVALID_LINK);

        User admin = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_LINK));
        if (!isAdmin(admin) || !admin.isActive()) throw new IllegalArgumentException(INVALID_LINK);

        admin.setPasswordHash(passwordEncoder.encode(newPassword));

        // The emailed link proves ownership, like the existing recovery code. Clear the sign-in protection
        // ONLY if wrong passwords caused it; a lock placed by a super admin on purpose must survive.
        boolean lockedByFailures = admin.getLoginFailedAttempts() >= LOCKOUT_FAILURES;
        admin.setLoginFailedAttempts(0);
        admin.setLoginLockedUntil(null);
        admin.setLastLoginFailedAt(null);
        admin.setAdminRecoveryRequired(false);
        if (lockedByFailures) {
            admin.setLocked(false);
            admin.setLockedAt(null);
            admin.setLockedBy(null);
        }
        userRepository.save(admin);

        refreshTokenRepository.revokeAllForUser(admin.getId());   // sign out every session
        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        emailService.sendAdminSecurityChangeEmail(admin, "password");
        log.info("Admin password reset completed");
    }

    // ------------------------------------------------------------------ PIN

    @Transactional
    public void requestPinReset(String email) {
        String normalized = normalize(email);
        if (normalized.isBlank() || !allowRequest("pin", normalized)) return;

        User admin = findAdmin(normalized).orElse(null);
        if (!canRecover(admin)) return;

        Instant now = Instant.now();
        pinChallenges.invalidateActive(admin.getId(), now);

        String otp = randomOtp();
        pinChallenges.save(AdminPinResetChallenge.builder()
                .adminId(admin.getId())
                .otpHash(passwordEncoder.encode(otp))
                .otpExpiresAt(now.plus(PIN_OTP_LIFETIME))
                .build());

        try {
            emailService.sendOtpEmail(admin.getEmail(), otp, (int) PIN_OTP_LIFETIME.toMinutes());
            log.info("Admin PIN recovery code sent");
        } catch (RuntimeException e) {
            log.warn("Admin PIN recovery email could not be sent: {}", e.toString());
        }
    }

    /** Verifies the emailed code and returns a one-time authorization. No login token is issued. */
    @Transactional
    public String verifyPinResetCode(String email, String code) {
        String normalized = normalize(email);
        if (normalized.isBlank() || code == null || !code.trim().matches("\\d{6}")) {
            throw new IllegalArgumentException(INVALID_CODE);
        }
        User admin = findAdmin(normalized).orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));
        if (!canRecover(admin)) throw new IllegalArgumentException(INVALID_CODE);

        AdminPinResetChallenge challenge = pinChallenges
                .findTopByAdminIdAndOtpUsedFalseOrderByCreatedAtDesc(admin.getId())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));

        if (challenge.isOtpExpired() || challenge.getOtpAttempts() >= PIN_OTP_MAX_ATTEMPTS) {
            throw new IllegalArgumentException(INVALID_CODE);
        }
        if (!passwordEncoder.matches(code.trim(), challenge.getOtpHash())) {
            challenge.setOtpAttempts(challenge.getOtpAttempts() + 1);
            pinChallenges.save(challenge);
            throw new IllegalArgumentException(INVALID_CODE);
        }

        String rawToken = randomToken();
        challenge.setOtpUsed(true);
        challenge.setResetTokenHash(hash(rawToken));
        challenge.setResetTokenExpiresAt(Instant.now().plus(PIN_TOKEN_LIFETIME));
        challenge.setResetTokenUsedAt(null);
        pinChallenges.save(challenge);
        return rawToken;
    }

    @Transactional
    public void resetPin(String rawToken, String newPin) {
        if (rawToken == null || rawToken.isBlank()) throw new IllegalArgumentException(INVALID_PIN_AUTH);
        if (newPin == null || !newPin.matches("\\d{4}")) throw new IllegalArgumentException("PIN must be exactly 4 digits");

        AdminPinResetChallenge challenge = pinChallenges
                .findByResetTokenHashAndResetTokenUsedAtIsNull(hash(rawToken.trim()))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_PIN_AUTH));
        if (challenge.isResetTokenExpired()) throw new IllegalArgumentException(INVALID_PIN_AUTH);

        User admin = userRepository.findById(challenge.getAdminId())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_PIN_AUTH));
        if (!isAdmin(admin) || !admin.isActive()) throw new IllegalArgumentException(INVALID_PIN_AUTH);

        admin.setPinHash(passwordEncoder.encode(newPin));
        admin.setPinAttempts(0);
        userRepository.save(admin);

        refreshTokenRepository.revokeAllForUser(admin.getId());   // a fresh sign-in is required
        challenge.setResetTokenUsedAt(Instant.now());
        pinChallenges.save(challenge);

        emailService.sendAdminSecurityChangeEmail(admin, "PIN");
        log.info("Admin PIN recovery completed");
    }

    // ------------------------------------------------------------------ housekeeping

    @Scheduled(fixedDelay = 900_000, initialDelay = 300_000)
    public void cleanup() {
        try {
            Instant now = Instant.now();
            requestLog.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    while (!e.getValue().isEmpty() && e.getValue().peekFirst().isBefore(now.minus(REQUEST_WINDOW))) {
                        e.getValue().removeFirst();
                    }
                    return e.getValue().isEmpty();
                }
            });
            if (requestLog.size() > 50_000) requestLog.clear();   // emergency brake against key flooding
            pinChallenges.deleteOlderThan(now.minus(Duration.ofDays(2)));
        } catch (Exception e) {
            log.warn("Admin recovery cleanup failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    private Optional<User> findAdmin(String email) {
        return userRepository.findByEmailAndRole(email, "SUPER_ADMIN")
                .or(() -> userRepository.findByEmailAndRole(email, "ADMIN"));
    }

    private static boolean isAdmin(User u) {
        return u != null && ("ADMIN".equals(u.getRole()) || "SUPER_ADMIN".equals(u.getRole()));
    }

    /** Active admin whose email has been verified (a typo'd, unverified address must never receive a reset). */
    private static boolean canRecover(User admin) {
        return isAdmin(admin) && admin.isActive() && admin.isEmailVerified();
    }

    /** 1 request per minute and 5 per 15 minutes for each typed email. */
    private boolean allowRequest(String kind, String email) {
        Instant now = Instant.now();
        Deque<Instant> times = requestLog.computeIfAbsent(kind + "|" + email, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(REQUEST_WINDOW))) times.removeFirst();
            if (!times.isEmpty() && times.peekLast().isAfter(now.minusSeconds(REQUEST_COOLDOWN_SECONDS))) return false;
            if (times.size() >= MAX_REQUESTS_PER_WINDOW) return false;
            times.addLast(now);
            return true;
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomOtp() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    /** SHA-256 + base64url: identical to AuthService.hashPasswordResetToken so stored tokens are compatible. */
    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash recovery token", e);
        }
    }
}
