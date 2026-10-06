package com.glr.deenwallet.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "pin_reset_challenges",
        indexes = {
                @Index(name = "idx_pin_reset_challenges_email_created", columnList = "email, created_at"),
                @Index(name = "idx_pin_reset_challenges_token", columnList = "reset_token_hash")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PinResetChallenge {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "otp_hash", nullable = false, length = 255)
    private String otpHash;

    @Column(name = "otp_expires_at", nullable = false)
    private Instant otpExpiresAt;

    @Column(name = "otp_attempts", nullable = false)
    private int otpAttempts;

    @Column(name = "otp_used", nullable = false)
    private boolean otpUsed;

    @Column(name = "reset_token_hash", length = 255)
    private String resetTokenHash;

    @Column(name = "reset_token_expires_at")
    private Instant resetTokenExpiresAt;

    @Column(name = "reset_token_used_at")
    private Instant resetTokenUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static PinResetChallenge create(
            UUID userId,
            String email,
            String otpHash,
            Instant otpExpiresAt
    ) {
        return PinResetChallenge.builder()
                .userId(userId)
                .email(email)
                .otpHash(otpHash)
                .otpExpiresAt(otpExpiresAt)
                .otpAttempts(0)
                .otpUsed(false)
                .build();
    }

    public boolean isOtpExpired() {
        return otpExpiresAt == null || otpExpiresAt.isBefore(Instant.now());
    }

    public boolean isResetTokenExpired() {
        return resetTokenExpiresAt == null
                || resetTokenExpiresAt.isBefore(Instant.now());
    }
}
