package com.glr.deenwallet.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BiometricCredentialRepository extends JpaRepository<BiometricCredential, UUID> {
    Optional<BiometricCredential> findByCredentialIdAndRevokedFalse(String credentialId);
    Optional<BiometricCredential> findByCredentialId(String credentialId);
    List<BiometricCredential> findByUserIdAndRevokedFalseOrderByCreatedAtDesc(UUID userId);
    Optional<BiometricCredential> findByIdAndUserId(UUID id, UUID userId);
}
