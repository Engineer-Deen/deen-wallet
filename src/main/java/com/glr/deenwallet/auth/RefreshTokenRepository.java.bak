package com.glr.deenwallet.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByJti(String jti);

    /**
     * Revoke every active refresh token belonging to a user.
     *
     * This is used after a successful password reset so that any existing
     * login sessions can no longer obtain new access tokens.
     */
    @Transactional
    @Modifying
    @Query("""
        UPDATE RefreshToken t
        SET t.revoked = true
        WHERE t.userId = :userId
          AND t.revoked = false
    """)
    int revokeAllForUser(@Param("userId") UUID userId);

    /**
     * Every token refresh revokes one row and inserts another, so this table grows
     * with every active user. An unknown jti is already rejected by AuthService
     * ("Invalid or revoked refresh token"), so deleting expired / long-revoked rows is safe.
     */
    @Transactional
    @Modifying
    @Query("""
        DELETE FROM RefreshToken t
        WHERE t.expiresAt < :now
           OR (t.revoked = true AND t.createdAt < :revokedBefore)
    """)
    int purge(
            @Param("now") Instant now,
            @Param("revokedBefore") Instant revokedBefore
    );
}