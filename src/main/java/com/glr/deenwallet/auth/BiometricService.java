package com.glr.deenwallet.auth;

import com.glr.deenwallet.config.JwtService;
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
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BiometricService {
    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(2);

    private final BiometricCredentialRepository credentialRepository;
    private final BiometricChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    @Transactional
    public ChallengeResponse createRegistrationChallenge(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        ensureUserCanAuthenticate(user);
        return createChallenge(userId, null, "REGISTRATION");
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
        return new RegistrationResponse(credential.getId(), credential.getCredentialId(), credential.getDeviceName());
    }

    @Transactional
    public ChallengeResponse createLoginChallenge(String credentialId) {
        BiometricCredential credential = credentialRepository.findByCredentialIdAndRevokedFalse(credentialId)
                .orElseThrow(() -> new IllegalArgumentException("Biometric login is not registered on this device"));
        User user = userRepository.findById(credential.getUserId()).orElseThrow(() -> new IllegalArgumentException("User account not found"));
        ensureUserCanAuthenticate(user);
        return createChallenge(user.getId(), credential.getCredentialId(), "LOGIN");
    }

    @Transactional
    public AuthController.LoginResponse authenticate(AuthenticateRequest request) {
        BiometricCredential credential = credentialRepository.findByCredentialIdAndRevokedFalse(request.credentialId())
                .orElseThrow(() -> new IllegalArgumentException("Biometric login is not registered on this device"));
        User user = userRepository.findById(credential.getUserId()).orElseThrow(() -> new IllegalArgumentException("User account not found"));
        ensureUserCanAuthenticate(user);

        BiometricChallenge challenge = consumeChallenge(request.challenge(), "LOGIN");
        if (!credential.getCredentialId().equals(challenge.getCredentialId()) || !user.getId().equals(challenge.getUserId())) {
            throw new IllegalArgumentException("Invalid biometric login challenge");
        }

        verifySignature(credential.getPublicKey(), request.signature(), request.challenge());
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

    private ChallengeResponse createChallenge(UUID userId, String credentialId, String purpose) {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        challengeRepository.save(BiometricChallenge.builder()
                .userId(userId).credentialId(credentialId).challenge(challenge).purpose(purpose)
                .expiresAt(Instant.now().plus(CHALLENGE_LIFETIME)).used(false).build());
        return new ChallengeResponse(challenge, CHALLENGE_LIFETIME.toSeconds());
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

    private void ensureUserCanAuthenticate(User user) {
        if (!user.isActive()) throw new IllegalStateException("Account is inactive. Please contact support.");
        if (user.isLocked()) throw new IllegalStateException("Account is locked. Please contact support.");
        if (!user.isEmailVerified()) throw new IllegalStateException("Please verify your email before logging in.");
    }

    private String normalizeDeviceName(String name) {
        if (name == null || name.isBlank()) return "Android device";
        return name.trim().substring(0, Math.min(name.trim().length(), 150));
    }

    public record ChallengeResponse(String challenge, long expiresInSeconds) {}
    public record RegistrationRequest(String credentialId, String publicKey, String signature, String challenge, String deviceName) {}
    public record RegistrationResponse(UUID id, String credentialId, String deviceName) {}
    public record AuthenticateRequest(String credentialId, String challenge, String signature) {}
    public record CredentialResponse(UUID id, String credentialId, String deviceName, Instant createdAt, Instant lastUsedAt) {}
}
