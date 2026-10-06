package com.glr.deenwallet.auth;

import com.glr.deenwallet.config.JwtService;
import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.otp.OtpService;
import com.glr.deenwallet.user.AccountNumberGenerator;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int USER_PIN_MAX_ATTEMPTS = 3;

    // User password login protection: account-based, not IP-only.
    private static final int USER_LOGIN_MAX_FAILURES = 5;
    private static final Duration USER_LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration USER_LOGIN_LOCK_DURATION = Duration.ofMinutes(5);

    // Password-reset tokens are short-lived, single-use, and never stored in
    // plaintext. The raw token is returned only to the caller that will place
    // it in the password-reset link sent to the user.
    private static final Duration PASSWORD_RESET_TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom PASSWORD_RESET_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountNumberGenerator accountNumberGenerator;
    private final EmailService emailService;
    private final OtpService otpService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final BiometricCredentialRepository biometricCredentialRepository;

    @Transactional
    public void register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        String username = request.getUsername().trim();
        String phone = request.getPhone().trim();

        if (userRepository.findByEmailAndRole(email, "USER").isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }

        if (userRepository.findByPhoneAndRole(phone, "USER").isPresent()) {
            throw new IllegalArgumentException("Phone number already registered");
        }

        User user = User.builder()
                .fullName(request.getFullName().trim())
                .username(username)
                .phone(phone)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .pinHash(passwordEncoder.encode(request.getPin()))
                .accountNumber(accountNumberGenerator.generate())
                .active(true)
                .locked(false)
                .pinAttempts(0)
                .emailVerified(false)
                .phoneVerified(false)
                .role("USER")
                .loginFailedAttempts(0)
                .adminRecoveryRequired(false)
                .build();

        userRepository.save(user);
        otpService.generateAndSend(email);
        log.info("User registered successfully: {}", email);
    }

    @Transactional
    public void confirmEmail(String email, String code) {
        String normalizedEmail = normalizeEmail(email);

        User user = userRepository.findByEmailAndRole(normalizedEmail, "USER")
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.isEmailVerified()) {
            return;
        }

        if (!otpService.verify(normalizedEmail, code.trim())) {
            throw new IllegalArgumentException("Invalid or expired verification code");
        }

        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("Email verified for {}", normalizedEmail);

        // Send the welcome email after successful first-time email verification.
        // Keep this separate from the OTP email so verification and welcome
        // messages remain distinct.
        emailService.sendWelcomeEmail(user);
    }

    @Transactional
    public void resendOtp(String email) {
        String normalizedEmail = normalizeEmail(email);

        User user = userRepository.findByEmailAndRole(normalizedEmail, "USER")
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("Email is already verified");
        }

        otpService.generateAndSend(normalizedEmail);
    }

    /**
     * Public user login. Administrator identities are deliberately isolated
     * from this endpoint.
     *
     * Password failures are tracked against the account. Five failures inside
     * the configured window temporarily pause password login for five minutes.
     * A successful login clears the failure state.
     */
    public AuthController.LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());

        /*
         * IMPORTANT:
         * USER and ADMIN/SUPER_ADMIN accounts are allowed to share an email
         * address. Therefore normal user authentication must explicitly query
         * for the USER role instead of using findByEmail().
         */
        User user = userRepository.findByEmailAndRole(email, "USER")
                .orElseThrow(() -> new IllegalArgumentException("User account not found"));

        checkUserLoginRateLimit(user);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            registerFailedUserLogin(user);

            User state = userRepository.findById(user.getId()).orElse(user);

            if (state.getLoginLockedUntil() != null
                    && state.getLoginLockedUntil().isAfter(Instant.now())) {

                throw new UserLoginRateLimitedException(
                        "Too many incorrect login attempts. Login is temporarily paused until "
                                + state.getLoginLockedUntil()
                                + ". Please try again later or contact support.",
                        state.getLoginLockedUntil()
                );
            }

            throw new InvalidUserLoginException(buildLoginFailureMessage(state));
        }

        // Password is correct. Account status and email verification are then
        // checked before issuing tokens.
        ensureUsable(user);

        if (!user.isEmailVerified()) {
            throw new EmailVerificationRequiredException(
                    "Please verify your email before logging in."
            );
        }

        clearUserLoginFailures(user);

        return issueLoginResponse(user);
    }

    /**
     * Creates a secure, short-lived password-reset token for the supplied user.
     *
     * The raw token is never stored in the database. Only its SHA-256 hash is
     * persisted. Previous unused reset tokens for the same user are invalidated
     * before the new token is created.
     *
     * The caller is responsible for placing the returned raw token into the
     * password-reset link sent to the user.
     */
    @Transactional
    public String createPasswordResetToken(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User is required");
        }

        Instant now = Instant.now();

        // Only one active reset token should remain valid for an account.
        passwordResetTokenRepository.markAllUsedForUser(user.getId(), now);

        byte[] randomBytes = new byte[32];
        PASSWORD_RESET_RANDOM.nextBytes(randomBytes);

        String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String tokenHash = hashPasswordResetToken(rawToken);

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(now.plus(PASSWORD_RESET_TOKEN_LIFETIME))
                .usedAt(null)
                .build();

        passwordResetTokenRepository.save(resetToken);

        return rawToken;
    }

    /**
     * Starts a password-reset request for a USER account.
     *
     * The returned value is the raw, one-time token and must only be used
     * internally to build the reset link sent by the email layer. It is never
     * persisted in plaintext.
     *
     * An empty Optional is returned when no USER account exists for the email.
     * This allows the controller to return the same response for existing and
     * non-existing addresses without revealing account existence.
     */
    @Transactional
    public Optional<String> requestPasswordReset(String email) {
        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail.isBlank()) {
            return Optional.empty();
        }

        Optional<User> userOptional =
                userRepository.findByEmailAndRole(normalizedEmail, "USER");

        if (userOptional.isEmpty()) {
            return Optional.empty();
        }

        User user = userOptional.get();

        String rawToken = createPasswordResetToken(user);

        emailService.sendPasswordResetEmail(user, rawToken);

        log.info("Password reset requested for USER account: {}", normalizedEmail);

        return Optional.of(rawToken);
    }

    /**
     * Resets the user's password using a raw password-reset token.
     *
     * The token must exist, must not have been used, and must not be expired.
     * Once accepted, it is immediately marked as used so it cannot be reused.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Invalid or expired password reset token");
        }

        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }

        String tokenHash = hashPasswordResetToken(rawToken.trim());

        PasswordResetToken resetToken =
                passwordResetTokenRepository.findByTokenHash(tokenHash)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Invalid or expired password reset token"));

        if (resetToken.isUsed() || resetToken.isExpired()) {
            throw new IllegalArgumentException("Invalid or expired password reset token");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Invalid or expired password reset token"));

        if (!"USER".equals(user.getRole())) {
            throw new IllegalArgumentException(
                    "Invalid or expired password reset token");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Revoke all existing refresh tokens after a successful password reset.
        // This prevents old sessions from obtaining new access tokens.
        refreshTokenRepository.revokeAllForUser(user.getId());

        // A reset password also cancels every phone's biometric login.
        biometricCredentialRepository.revokeAllForUser(user.getId());

        resetToken.setUsedAt(Instant.now());
        passwordResetTokenRepository.save(resetToken);
    }

    private String hashPasswordResetToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    rawToken.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every standard Java runtime.
            throw new IllegalStateException(
                    "Unable to create password reset token",
                    e
            );
        }
    }

    @Transactional
    public AuthController.LoginResponse refreshToken(String rawRefreshToken) {
        if (!jwtService.isValid(rawRefreshToken)
                || !jwtService.isRefreshToken(rawRefreshToken)) {

            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        String jti = jwtService.extractJti(rawRefreshToken);

        RefreshToken stored = refreshTokenRepository.findByJti(jti)
                .orElseThrow(() ->
                        new IllegalArgumentException("Invalid or revoked refresh token"));

        if (stored.isRevoked()
                || stored.getExpiresAt().isBefore(Instant.now())) {

            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        UUID userId = UUID.fromString(
                jwtService.extractUserId(rawRefreshToken)
        );

        if (!stored.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (isAdmin(user)) {
            throw new IllegalArgumentException("Invalid user refresh token");
        }

        ensureUsable(user);

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        return issueLoginResponse(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }

        try {
            String jti = jwtService.extractJti(rawRefreshToken);

            refreshTokenRepository.findByJti(jti).ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });

        } catch (Exception ignored) {
            // Logout is intentionally idempotent.
        }
    }

    @Transactional
    public void setPin(UUID userId, String pin, String currentPassword) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        ensureUsable(user);

        // SECURITY: Changing the PIN is a sensitive action (the PIN gates
        // money movement in the app), so it must re-confirm the account
        // password rather than trusting the bearer token alone. Without
        // this, a stolen/short-lived access token would be enough to take
        // over the PIN outright.
        if (currentPassword == null
                || currentPassword.isBlank()
                || !passwordEncoder.matches(
                currentPassword,
                user.getPasswordHash())) {

            throw new IllegalArgumentException("Current password is incorrect");
        }

        user.setPinHash(passwordEncoder.encode(pin));
        user.setPinAttempts(0);
        userRepository.save(user);
    }

    /**
     * PIN verification remains separate from password login protection.
     * Three incorrect PINs permanently block the account until an admin
     * unlocks it.
     */
    public void verifyPin(UUID userId, String pin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        ensureUsable(user);

        if (pin == null || !pin.matches("\\d{4}")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits");
        }

        if (user.getPinHash() == null || user.getPinHash().isBlank()) {
            throw new PinNotSetException(
                    "PIN has not been set. Please set your PIN in Profile."
            );
        }

        if (passwordEncoder.matches(pin, user.getPinHash())) {
            userRepository.clearFailedPinAttempts(userId);
            return;
        }

        Instant now = Instant.now();

        int changed = userRepository.recordFailedPinAttempt(
                userId,
                now
        );

        User lockedState = userRepository.findById(userId).orElse(user);

        int attempts = lockedState.getPinAttempts();

        if (attempts >= USER_PIN_MAX_ATTEMPTS
                || lockedState.isLocked()) {

            // Only the request that actually changed the account into the
            // locked state sends the notification. Replayed/concurrent PIN
            // submissions do not send duplicate lock emails.
            // (changed == 1 means this request did the locking. The old check also
            // demanded attempts == 3 exactly, which silently skipped the email
            // whenever the counter was anything else.)
            if (changed == 1 && lockedState.isLocked()) {
                log.info("Account {} locked after {} wrong PIN attempts - queueing lock email",
                        lockedState.getAccountNumber(), attempts);
                try {
                    emailService.sendAccountLockedEmail(lockedState, "pin");
                } catch (Exception e) {
                    log.error(
                            "Unable to queue user lock email for {}",
                            lockedState.getEmail(),
                            e
                    );
                }
            } else {
                log.info("Account {} already locked (changed={}, attempts={}) - no new lock email",
                        lockedState.getAccountNumber(), changed, attempts);
            }

            throw new AccountPinLockedException(
                    "Account blocked because the PIN was entered incorrectly 3 times. Please contact support to reactivate your account."
            );
        }

        throw new IncorrectPinException(
                "Incorrect PIN. "
                        + (USER_PIN_MAX_ATTEMPTS - attempts)
                        + " attempt(s) remaining."
        );
    }

    private void checkUserLoginRateLimit(User user) {
        Instant now = Instant.now();

        Instant lastFailure = user.getLastLoginFailedAt();
        Instant lockedUntil = user.getLoginLockedUntil();

        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            throw new UserLoginRateLimitedException(
                    "Too many incorrect login attempts. Login is temporarily paused until "
                            + lockedUntil
                            + ". Please try again later or contact support.",
                    lockedUntil
            );
        }

        // If the temporary lock expired, start a fresh failure window.
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            clearUserLoginFailures(user);
            return;
        }

        // Also reset stale failures after the rolling window expires.
        if (lastFailure != null
                && lastFailure.plus(USER_LOGIN_WINDOW).isBefore(now)) {

            clearUserLoginFailures(user);
        }
    }

    private void registerFailedUserLogin(User user) {
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(USER_LOGIN_LOCK_DURATION);

        userRepository.recordUserPasswordFailure(
                user.getId(),
                now,
                lockedUntil
        );
    }

    private String buildLoginFailureMessage(User user) {
        User state = userRepository.findById(user.getId()).orElse(user);

        int failures = state.getLoginFailedAttempts();

        if (state.getLoginLockedUntil() != null
                && state.getLoginLockedUntil().isAfter(Instant.now())) {

            return "Too many incorrect login attempts. Login is temporarily paused until "
                    + state.getLoginLockedUntil()
                    + ". Please try again later or contact support.";
        }

        int remaining = Math.max(
                0,
                USER_LOGIN_MAX_FAILURES - failures
        );

        return "Invalid email or password. "
                + remaining
                + " login attempt(s) remaining before a temporary security pause.";
    }

    private void clearUserLoginFailures(User user) {
        if (user.getLoginFailedAttempts() != 0
                || user.getLoginLockedUntil() != null
                || user.getLastLoginFailedAt() != null) {

            userRepository.clearLoginFailures(user.getId());
        }
    }

    AuthController.LoginResponse issueLoginResponse(User user) {
        String role = user.getRole() == null
                ? "USER"
                : user.getRole();

        String access = jwtService.generateAccessToken(
                user.getId().toString(),
                role
        );

        String refresh = jwtService.generateRefreshToken(
                user.getId().toString(),
                role
        );

        refreshTokenRepository.save(
                RefreshToken.builder()
                        .jti(jwtService.extractJti(refresh))
                        .userId(user.getId())
                        .expiresAt(
                                jwtService.extractExpiration(refresh)
                                        .toInstant()
                        )
                        .revoked(false)
                        .build()
        );

        return new AuthController.LoginResponse(
                access,
                refresh,
                user.getFirstName(),
                user.getAccountNumber(),
                role
        );
    }

    private void ensureUsable(User user) {
        if (!user.isActive()) {
            throw new IllegalStateException(
                    "Account is inactive. Please contact support."
            );
        }

        if (user.isLocked()) {
            throw new AccountLockedException(
                    "Account is locked. Please contact support."
            );
        }
    }

    private boolean isAdmin(User user) {
        return "ADMIN".equals(user.getRole())
                || "SUPER_ADMIN".equals(user.getRole());
    }

    private String normalizeEmail(String email) {
        return email == null
                ? ""
                : email.trim().toLowerCase(Locale.ROOT);
    }

    public static class InvalidUserLoginException
            extends IllegalArgumentException {

        public InvalidUserLoginException(String message) {
            super(message);
        }
    }

    public static class EmailVerificationRequiredException
            extends IllegalStateException {

        public EmailVerificationRequiredException(String message) {
            super(message);
        }
    }

    public static class UserLoginRateLimitedException
            extends IllegalStateException {

        private final Instant lockedUntil;

        public UserLoginRateLimitedException(
                String message,
                Instant lockedUntil
        ) {
            super(message);
            this.lockedUntil = lockedUntil;
        }

        public Instant getLockedUntil() {
            return lockedUntil;
        }
    }

    public static class IncorrectPinException
            extends IllegalArgumentException {

        public IncorrectPinException(String message) {
            super(message);
        }
    }

    public static class AccountPinLockedException
            extends IllegalStateException {

        public AccountPinLockedException(String message) {
            super(message);
        }
    }

    public static class AccountLockedException
            extends IllegalStateException {

        public AccountLockedException(String message) {
            super(message);
        }
    }

    public static class PinNotSetException
            extends IllegalStateException {

        public PinNotSetException(String message) {
            super(message);
        }
    }
}