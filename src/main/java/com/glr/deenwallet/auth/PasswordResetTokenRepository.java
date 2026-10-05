package com.glr.deenwallet.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Transactional
    @Modifying
    @Query("""
        UPDATE PasswordResetToken t
        SET t.usedAt = :usedAt
        WHERE t.userId = :userId
          AND t.usedAt IS NULL
    """)
    int markAllUsedForUser(
            @Param("userId") UUID userId,
            @Param("usedAt") Instant usedAt
    );

    @Transactional
    @Modifying
    @Query("""
        DELETE FROM PasswordResetToken t
        WHERE t.expiresAt < :now
           OR t.usedAt IS NOT NULL
    """)
    int deleteExpiredOrUsed(@Param("now") Instant now);
}