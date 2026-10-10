package com.glr.deenwallet.transaction;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One row per recovery event on a failed payout (who, what, when, result). Never updated or deleted. */
@Entity
@Table(name = "payout_recovery_actions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PayoutRecoveryAction {
    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, length = 30, updatable = false)
    private String action;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_email", length = 150, updatable = false)
    private String actorEmail;

    @Column(length = 1000, updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
