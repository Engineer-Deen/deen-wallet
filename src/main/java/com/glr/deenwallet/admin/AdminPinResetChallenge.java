package com.glr.deenwallet.admin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One admin "Forgot PIN" attempt: the emailed code, then (after it is verified) a one-time authorization. */
@Entity
@Table(name = "admin_pin_reset_challenges")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPinResetChallenge {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "otp_hash", nullable = false, length = 100)
    private String otpHash;

    @Column(name = "otp_expires_at", nullable = false)
    private Instant otpExpiresAt;

    @Column(name = "otp_attempts", nullable = false)
    private int otpAttempts;

    @Column(name = "otp_used", nullable = false)
    private boolean otpUsed;

    @Column(name = "reset_token_hash", length = 64)
    private String resetTokenHash;

    @Column(name = "reset_token_expires_at")
    private Instant resetTokenExpiresAt;

    @Column(name = "reset_token_used_at")
    private Instant resetTokenUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isOtpExpired() {
        return otpExpiresAt == null || !otpExpiresAt.isAfter(Instant.now());
    }

    public boolean isResetTokenExpired() {
        return resetTokenExpiresAt == null || !resetTokenExpiresAt.isAfter(Instant.now());
    }
}
