package com.glr.deenwallet.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BiometricCredentialRepository extends JpaRepository<BiometricCredential, UUID> {
    Optional<BiometricCredential> findByCredentialIdAndRevokedFalse(String credentialId);
    Optional<BiometricCredential> findByCredentialId(String credentialId);
    List<BiometricCredential> findByUserIdAndRevokedFalseOrderByCreatedAtDesc(UUID userId);
    Optional<BiometricCredential> findByIdAndUserId(UUID id, UUID userId);

    // Used when a password is reset: every phone has to prove itself again.
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BiometricCredential c SET c.revoked = true WHERE c.userId = :userId AND c.revoked = false")
    int revokeAllForUser(@Param("userId") UUID userId);
}
