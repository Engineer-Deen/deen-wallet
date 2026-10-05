package com.glr.deenwallet.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, UUID> {

    Optional<EmailOtp> findTopByEmailAndUsedFalseOrderByCreatedAtDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, Instant after);

    /** OTPs expire after minutes; nothing needs them after a couple of days. */
    @Transactional
    @Modifying
    @Query("DELETE FROM EmailOtp o WHERE o.createdAt < :cutoff")
    int purgeOlderThan(@Param("cutoff") Instant cutoff);
}
