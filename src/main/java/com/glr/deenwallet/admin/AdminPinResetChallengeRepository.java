package com.glr.deenwallet.admin;

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

public interface AdminPinResetChallengeRepository extends JpaRepository<AdminPinResetChallenge, UUID> {

    /** Row-locked so two parallel code attempts cannot both pass the attempt counter. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AdminPinResetChallenge> findTopByAdminIdAndOtpUsedFalseOrderByCreatedAtDesc(UUID adminId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AdminPinResetChallenge> findByResetTokenHashAndResetTokenUsedAtIsNull(String resetTokenHash);

    /** A new request kills every earlier unfinished request for the same admin. */
    @Transactional
    @Modifying
    @Query("UPDATE AdminPinResetChallenge c SET c.otpUsed = true, c.resetTokenUsedAt = :now "
            + "WHERE c.adminId = :adminId AND c.resetTokenUsedAt IS NULL")
    int invalidateActive(@Param("adminId") UUID adminId, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("DELETE FROM AdminPinResetChallenge c WHERE c.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
