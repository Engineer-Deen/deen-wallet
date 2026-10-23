package com.glr.deenwallet.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One line in the KYC trail: who did what and when. Never updated or deleted. */
@Entity
@Table(name = "kyc_audit")
public class KycAudit {

    @Id
    private UUID id;

    @Column(name = "submission_id", updatable = false)
    private UUID submissionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 30, updatable = false)
    private String action;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_email", length = 254, updatable = false)
    private String actorEmail;

    @Column(length = 500, updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KycAudit() { }

    public KycAudit(UUID submissionId, UUID userId, String action, UUID actorId, String actorEmail, String detail) {
        this.submissionId = submissionId;
        this.userId = userId;
        this.action = action;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.detail = detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail);
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public UUID getUserId() { return userId; }
    public String getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
