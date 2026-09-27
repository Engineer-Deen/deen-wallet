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

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountNumberGenerator accountNumberGenerator;
    private final EmailService emailService;
    private final OtpService otpService;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public void register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        String username = request.getUsername().trim();
        String phone = request.getPhone().trim();

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }
        if (userRepository.findByPhone(phone).isPresent()) {
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
        User user = userRepository.findByEmail(normalizedEmail)
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
    }

    @Transactional
    public void resendOtp(String email) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmail(normalizedEmail)
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

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User account not found"));

        // Never allow an administrator identity to authenticate through the
        // normal user application.
        if (isAdmin(user)) {
            throw new IllegalArgumentException("User account not found");
        }

        checkUserLoginRateLimit(user);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            registerFailedUserLogin(user);
            User state = userRepository.findById(user.getId()).orElse(user);
            if (state.getLoginLockedUntil() != null && state.getLoginLockedUntil().isAfter(Instant.now())) {
                throw new UserLoginRateLimitedException(
                        "Too many incorrect login attempts. Login is temporarily paused until "
                                + state.getLoginLockedUntil() + ". Please try again later or contact support.",
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

    @Transactional
    public AuthController.LoginResponse refreshToken(String rawRefreshToken) {
        if (!jwtService.isValid(rawRefreshToken) || !jwtService.isRefreshToken(rawRefreshToken)) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        String jti = jwtService.extractJti(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByJti(jti)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or revoked refresh token"));

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }

        UUID userId = UUID.fromString(jwtService.extractUserId(rawRefreshToken));

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
        if (currentPassword == null || currentPassword.isBlank()
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
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
            throw new PinNotSetException("PIN has not been set. Please set your PIN in Profile.");
        }

        if (passwordEncoder.matches(pin, user.getPinHash())) {
            userRepository.clearFailedPinAttempts(userId);
            return;
        }

        Instant now = Instant.now();
        int changed = userRepository.recordFailedPinAttempt(userId, now);

        User lockedState = userRepository.findById(userId).orElse(user);
        int attempts = lockedState.getPinAttempts();

        if (attempts >= USER_PIN_MAX_ATTEMPTS || lockedState.isLocked()) {
            // Only the request that actually changed the account into the
            // locked state sends the notification. Replayed/concurrent PIN
            // submissions do not send duplicate lock emails.
            if (changed == 1 && attempts == USER_PIN_MAX_ATTEMPTS) {
                try {
                    emailService.sendAccountLockedEmail(lockedState);
                } catch (Exception e) {
                    log.warn("Unable to queue user lock email for {}", lockedState.getEmail(), e);
                }
            }

            throw new AccountPinLockedException(
                    "Account blocked because the PIN was entered incorrectly 3 times. Please contact support to reactivate your account."
            );
        }

        throw new IncorrectPinException(
                "Incorrect PIN. " + (USER_PIN_MAX_ATTEMPTS - attempts) + " attempt(s) remaining."
        );
    }

    private void checkUserLoginRateLimit(User user) {
        Instant now = Instant.now();
        Instant lastFailure = user.getLastLoginFailedAt();
        Instant lockedUntil = user.getLoginLockedUntil();

        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            throw new UserLoginRateLimitedException(
                    "Too many incorrect login attempts. Login is temporarily paused until "
                            + lockedUntil + ". Please try again later or contact support.",
                    lockedUntil
            );
        }

        // If the temporary lock expired, start a fresh failure window.
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            clearUserLoginFailures(user);
            return;
        }

        // Also reset stale failures after the rolling window expires.
        if (lastFailure != null && lastFailure.plus(USER_LOGIN_WINDOW).isBefore(now)) {
            clearUserLoginFailures(user);
        }
    }

    private void registerFailedUserLogin(User user) {
        Instant now = Instant.now();
        Instant lockedUntil = now.plus(USER_LOGIN_LOCK_DURATION);
        userRepository.recordUserPasswordFailure(user.getId(), now, lockedUntil);
    }

    private String buildLoginFailureMessage(User user) {
        User state = userRepository.findById(user.getId()).orElse(user);
        int failures = state.getLoginFailedAttempts();

        if (state.getLoginLockedUntil() != null
                && state.getLoginLockedUntil().isAfter(Instant.now())) {
            return "Too many incorrect login attempts. Login is temporarily paused until "
                    + state.getLoginLockedUntil() + ". Please try again later or contact support.";
        }

        int remaining = Math.max(0, USER_LOGIN_MAX_FAILURES - failures);
        return "Invalid email or password. " + remaining
                + " login attempt(s) remaining before a temporary security pause.";
    }

    private void clearUserLoginFailures(User user) {
        if (user.getLoginFailedAttempts() != 0
                || user.getLoginLockedUntil() != null
                || user.getLastLoginFailedAt() != null) {
            userRepository.clearLoginFailures(user.getId());
        }
    }

    private AuthController.LoginResponse issueLoginResponse(User user) {
        String role = user.getRole() == null ? "USER" : user.getRole();
        String access = jwtService.generateAccessToken(user.getId().toString(), role);
        String refresh = jwtService.generateRefreshToken(user.getId().toString(), role);

        refreshTokenRepository.save(
                RefreshToken.builder()
                        .jti(jwtService.extractJti(refresh))
                        .userId(user.getId())
                        .expiresAt(jwtService.extractExpiration(refresh).toInstant())
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
            throw new IllegalStateException("Account is inactive. Please contact support.");
        }
        if (user.isLocked()) {
            throw new AccountLockedException("Account is locked. Please contact support.");
        }
    }

    private boolean isAdmin(User user) {
        return "ADMIN".equals(user.getRole()) || "SUPER_ADMIN".equals(user.getRole());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static class InvalidUserLoginException extends IllegalArgumentException {
        public InvalidUserLoginException(String message) {
            super(message);
        }
    }

    public static class EmailVerificationRequiredException extends IllegalStateException {
        public EmailVerificationRequiredException(String message) {
            super(message);
        }
    }

    public static class UserLoginRateLimitedException extends IllegalStateException {
        private final Instant lockedUntil;

        public UserLoginRateLimitedException(String message, Instant lockedUntil) {
            super(message);
            this.lockedUntil = lockedUntil;
        }

        public Instant getLockedUntil() {
            return lockedUntil;
        }
    }

    public static class IncorrectPinException extends IllegalArgumentException {
        public IncorrectPinException(String message) {
            super(message);
        }
    }

    public static class AccountPinLockedException extends IllegalStateException {
        public AccountPinLockedException(String message) {
            super(message);
        }
    }

    public static class AccountLockedException extends IllegalStateException {
        public AccountLockedException(String message) {
            super(message);
        }
    }

    public static class PinNotSetException extends IllegalStateException {
        public PinNotSetException(String message) {
            super(message);
        }
    }
}
