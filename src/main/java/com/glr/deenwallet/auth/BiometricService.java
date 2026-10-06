package com.glr.deenwallet.auth;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Biometric login.
 *
 * Every credential belongs to exactly one account on one phone. A phone can
 * hold one credential per account, and the app must say WHICH account it is
 * signing in to. The server checks that the credential really belongs to that
 * account, so one account's biometric can never open another account.
 */
@Service
@RequiredArgsConstructor
public class BiometricService {
    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(2);

    // Too many bad biometric proofs for one credential temporarily block it.
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(10);

    // An account can keep a handful of phones, not an unlimited number.
    private static final int MAX_ACTIVE_CREDENTIALS_PER_USER = 5;

    private final BiometricCredentialRepository credentialRepository;
    private final BiometricChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    private final Map<String, FailureState> failures = new ConcurrentHashMap<>();

    @Transactional
    public ChallengeResponse createRegistrationChallenge(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        ensureUserCanAuthenticate(user);
        return createChallenge(user, null, "REGISTRATION");
    }

    @Transactional
    public RegistrationResponse register(UUID userId, RegistrationRequest request) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        ensureUserCanAuthenticate(user);

        BiometricChallenge challenge = consumeChallenge(request.challenge(), "REGISTRATION");
        if (!userId.equals(challenge.getUserId())) throw new IllegalArgumentException("Invalid biometric registration challenge");

        verifySignature(request.publicKey(), request.signature(), request.challenge());

        credentialRepository.findByCredentialIdAndRevokedFalse(request.credentialId()).ifPresent(existing -> {
            if (!userId.equals(existing.getUserId())) throw new IllegalArgumentException("Biometric credential is already registered");
        });

        BiometricCredential credential = credentialRepository.findByCredentialId(request.credentialId()).orElse(null);
        if (credential == null) {
            if (credentialRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId).size() >= MAX_ACTIVE_CREDENTIALS_PER_USER) {
                throw new IllegalArgumentException("This account already has the maximum number of biometric devices. Disable one first.");
            }
            credential = BiometricCredential.builder()
                    .userId(userId)
                    .credentialId(request.credentialId())
                    .publicKey(request.publicKey())
                    .deviceName(normalizeDeviceName(request.deviceName()))
                    .revoked(false)
                    .build();
        } else {
            if (!userId.equals(credential.getUserId())) {
                throw new IllegalArgumentException("Biometric credential is already registered to another account");
            }
            credential.setPublicKey(request.publicKey());
            credential.setDeviceName(normalizeDeviceName(request.deviceName()));
            credential.setRevoked(false);
        }
        credentialRepository.save(credential);

        // Re-enabling on the same phone replaces the old credential instead of
        // leaving an unusable one behind.
        String replaces = request.replacesCredentialId();
        if (replaces != null && !replaces.isBlank() && !replaces.equals(request.credentialId())) {
            credentialRepository.findByCredentialId(replaces).ifPresent(old -> {
                if (userId.equals(old.getUserId()) && !old.isRevoked()) {
                    old.setRevoked(true);
                    credentialRepository.save(old);
                }
            });
        }

        return new RegistrationResponse(credential.getId(), credential.getCredentialId(), credential.getDeviceName(), describe(user));
    }

    @Transactional
    public ChallengeResponse createLoginChallenge(LoginChallengeRequest request) {
        String credentialId = request.credentialId();
        ensureNotBlocked(credentialId);
        BiometricCredential credential = credentialRepository.findByCredentialIdAndRevokedFalse(credentialId)
                .orElseThrow(() -> new IllegalArgumentException("Biometric login is no longer set up for this account. Log in with your password and enable it again."));
        ensureCredentialBelongsToAccount(credential, request.accountId());
        User user = userRepository.findById(credential.getUserId()).orElseThrow(() -> new IllegalArgumentException("User account not found"));
        ensureUserCanAuthenticate(user);
        return createChallenge(user, credential.getCredentialId(), "LOGIN");
    }

    // A rejected proof must still burn its challenge, so a failed attempt is
    // not rolled back (noRollbackFor) and the same challenge cannot be tried again.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public AuthController.LoginResponse authenticate(AuthenticateRequest request) {
        ensureNotBlocked(request.credentialId());

        BiometricCredential credential = credentialRepository.findByCredentialIdAndRevokedFalse(request.credentialId())
                .orElseThrow(() -> new IllegalArgumentException("Biometric login is no longer set up for this account. Log in with your password and enable it again."));
        ensureCredentialBelongsToAccount(credential, request.accountId());
        User user = userRepository.findById(credential.getUserId()).orElseThrow(() -> new IllegalArgumentException("User account not found"));
        ensureUserCanAuthenticate(user);

        try {
            BiometricChallenge challenge = consumeChallenge(request.challenge(), "LOGIN");
            if (!credential.getCredentialId().equals(challenge.getCredentialId()) || !user.getId().equals(challenge.getUserId())) {
                throw new IllegalArgumentException("Invalid biometric login challenge");
            }
            verifySignature(credential.getPublicKey(), request.signature(), request.challenge());
        } catch (IllegalArgumentException e) {
            recordFailure(request.credentialId());
            throw e;
        }

        failures.remove(request.credentialId());
        credential.setLastUsedAt(Instant.now());
        credentialRepository.save(credential);
        return authService.issueLoginResponse(user);
    }

    @Transactional
    public void revoke(UUID userId, UUID credentialId) {
        BiometricCredential credential = credentialRepository.findByIdAndUserId(credentialId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Biometric credential not found"));
        credential.setRevoked(true);
        credentialRepository.save(credential);
    }

    public java.util.List<CredentialResponse> list(UUID userId) {
        return credentialRepository.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(userId).stream()
                .map(c -> new CredentialResponse(c.getId(), c.getCredentialId(), c.getDeviceName(), c.getCreatedAt(), c.getLastUsedAt()))
                .toList();
    }

    // ------------------------------------------------------------------

    private ChallengeResponse createChallenge(User user, String credentialId, String purpose) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        challengeRepository.save(BiometricChallenge.builder()
                .userId(user.getId()).credentialId(credentialId).challenge(challenge).purpose(purpose)
                .expiresAt(Instant.now().plus(CHALLENGE_LIFETIME)).used(false).build());
        return new ChallengeResponse(challenge, CHALLENGE_LIFETIME.toSeconds(), describe(user));
    }

    private BiometricChallenge consumeChallenge(String rawChallenge, String purpose) {
        BiometricChallenge challenge = challengeRepository.findByChallengeAndUsedFalse(rawChallenge)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired biometric challenge"));
        if (!purpose.equals(challenge.getPurpose()) || challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Invalid or expired biometric challenge");
        }
        challenge.setUsed(true);
        challengeRepository.save(challenge);
        return challenge;
    }

    private void verifySignature(String publicKeyBase64, String signatureBase64, String challenge) {
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(publicKeyBase64);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureBase64);
            PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) throw new IllegalArgumentException("Biometric signature verification failed");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid biometric credential", e); }
    }

    /**
     * The app names the account the person chose. If the credential belongs to
     * a different account the sign-in is refused, so a stale or mixed-up
     * credential can never log someone into the wrong account.
     */
    private void ensureCredentialBelongsToAccount(BiometricCredential credential, UUID expectedAccountId) {
        if (expectedAccountId != null && !expectedAccountId.equals(credential.getUserId())) {
            throw new IllegalArgumentException("This biometric login belongs to a different account. Choose the right account and try again.");
        }
    }

    private void ensureUserCanAuthenticate(User user) {
        if (!user.isActive()) throw new IllegalStateException("Account is inactive. Please contact support.");
        if (user.isLocked()) throw new IllegalStateException("Account is locked. Please contact support.");
        if (!user.isEmailVerified()) throw new IllegalStateException("Please verify your email before logging in.");
    }

    private void ensureNotBlocked(String credentialId) {
        if (credentialId == null) return;
        FailureState state = failures.get(credentialId);
        if (state == null) return;
        synchronized (state) {
            if (state.blockedUntil != null && state.blockedUntil.isAfter(Instant.now())) {
                throw new AuthService.UserLoginRateLimitedException(
                        "Too many biometric attempts. Please wait a few minutes or log in with your password.",
                        state.blockedUntil);
            }
        }
    }

    private void recordFailure(String credentialId) {
        if (credentialId == null) return;
        FailureState state = failures.computeIfAbsent(credentialId, k -> new FailureState());
        synchronized (state) {
            Instant now = Instant.now();
            while (!state.times.isEmpty() && state.times.peekFirst().isBefore(now.minus(FAILURE_WINDOW))) {
                state.times.pollFirst();
            }
            state.times.addLast(now);
            if (state.times.size() >= MAX_FAILED_ATTEMPTS) {
                state.blockedUntil = now.plus(BLOCK_DURATION);
                state.times.clear();
            }
        }
    }

    private AccountInfo describe(User user) {
        return new AccountInfo(user.getId(), user.getFirstName(), maskEmail(user.getEmail()), user.getAccountNumber());
    }

    static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "";
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private String normalizeDeviceName(String name) {
        if (name == null || name.isBlank()) return "Android device";
        return name.trim().substring(0, Math.min(name.trim().length(), 150));
    }

    private static final class FailureState {
        final Deque<Instant> times = new ArrayDeque<>();
        Instant blockedUntil;
    }

    public record AccountInfo(UUID id, String firstName, String maskedEmail, String accountNumber) {}
    public record ChallengeResponse(String challenge, long expiresInSeconds, AccountInfo account) {}
    public record LoginChallengeRequest(String credentialId, UUID accountId) {}
    public record RegistrationRequest(String credentialId, String publicKey, String signature, String challenge, String deviceName, String replacesCredentialId) {}
    public record RegistrationResponse(UUID id, String credentialId, String deviceName, AccountInfo account) {}
    public record AuthenticateRequest(String credentialId, String challenge, String signature, UUID accountId) {}
    public record CredentialResponse(UUID id, String credentialId, String deviceName, Instant createdAt, Instant lastUsedAt) {}
}
