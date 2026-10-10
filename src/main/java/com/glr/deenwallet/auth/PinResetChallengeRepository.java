package com.glr.deenwallet.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PinResetChallengeRepository extends JpaRepository<PinResetChallenge, java.util.UUID> {

    Optional<PinResetChallenge> findTopByEmailOrderByCreatedAtDesc(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PinResetChallenge> findTopByEmailAndOtpUsedFalseOrderByCreatedAtDesc(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PinResetChallenge> findByResetTokenHashAndResetTokenUsedAtIsNull(String resetTokenHash);

    long countByEmailAndCreatedAtAfter(String email, Instant createdAt);

    @Modifying
    @Query("""
            update PinResetChallenge p
               set p.otpUsed = true,
                   p.resetTokenUsedAt = :now
             where p.email = :email
               and p.resetTokenUsedAt is null
            """)
    int invalidateActiveChallenges(
            @Param("email") String email,
            @Param("now") Instant now
    );
}
