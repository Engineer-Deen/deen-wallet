package com.glr.deenwallet.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
        SET t.revoked = true, t.revokedAt = null
        WHERE t.userId = :userId
          AND (t.revoked = false OR t.revokedAt IS NOT NULL)
    """)
    int revokeAllForUser(@Param("userId") UUID userId);

    /** Logins of this customer that can still be refreshed. */
    @Query("""
        SELECT t FROM RefreshToken t
        WHERE t.userId = :userId
          AND t.revoked = false
          AND t.expiresAt > :now
        ORDER BY t.createdAt DESC
    """)
    List<RefreshToken> findActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Ends one session completely. revokedAt is cleared too, so a token that was rotated a moment ago
     * cannot be presented again during the short reuse grace window.
     */
    @Transactional
    @Modifying
    @Query("""
        UPDATE RefreshToken t
        SET t.revoked = true, t.revokedAt = null
        WHERE t.userId = :userId
          AND t.sessionId = :sessionId
          AND (t.revoked = false OR t.revokedAt IS NOT NULL)
    """)
    int revokeSession(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    @Query("""
        SELECT DISTINCT t.sessionId FROM RefreshToken t
        WHERE t.userId = :userId
          AND t.deviceKey = :deviceKey
          AND t.revoked = false
          AND t.expiresAt > :now
    """)
    List<UUID> activeSessionIdsForDevice(@Param("userId") UUID userId,
                                         @Param("deviceKey") String deviceKey,
                                         @Param("now") Instant now);

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