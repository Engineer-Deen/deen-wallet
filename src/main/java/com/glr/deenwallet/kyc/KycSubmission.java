package com.glr.deenwallet.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One identity verification attempt by a customer. */
@Entity
@Table(name = "kyc_submissions")
public class KycSubmission {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private KycStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 20)
    private KycDocumentType documentType;

    @Column(name = "full_name_on_document", length = 120)
    private String fullNameOnDocument;

    @Column(name = "document_number_enc", length = 600)
    private String documentNumberEnc;

    @Column(name = "document_number_hash", length = 64)
    private String documentNumberHash;

    @Column(name = "document_last4", length = 4)
    private String documentLast4;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "document_expiry_date")
    private LocalDate documentExpiryDate;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_by_email", length = 254)
    private String reviewedByEmail;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KycSubmission() { }

    public static KycSubmission draftFor(UUID userId) {
        KycSubmission s = new KycSubmission();
        s.userId = userId;
        s.status = KycStatus.DRAFT;
        return s;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public KycStatus getStatus() { return status; }
    public void setStatus(KycStatus status) { this.status = status; }
    public KycDocumentType getDocumentType() { return documentType; }
    public void setDocumentType(KycDocumentType documentType) { this.documentType = documentType; }
    public String getFullNameOnDocument() { return fullNameOnDocument; }
    public void setFullNameOnDocument(String v) { this.fullNameOnDocument = v; }
    public String getDocumentNumberEnc() { return documentNumberEnc; }
    public void setDocumentNumberEnc(String v) { this.documentNumberEnc = v; }
    public String getDocumentNumberHash() { return documentNumberHash; }
    public void setDocumentNumberHash(String v) { this.documentNumberHash = v; }
    public String getDocumentLast4() { return documentLast4; }
    public void setDocumentLast4(String v) { this.documentLast4 = v; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate v) { this.dateOfBirth = v; }
    public LocalDate getDocumentExpiryDate() { return documentExpiryDate; }
    public void setDocumentExpiryDate(LocalDate v) { this.documentExpiryDate = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID v) { this.reviewedBy = v; }
    public String getReviewedByEmail() { return reviewedByEmail; }
    public void setReviewedByEmail(String v) { this.reviewedByEmail = v; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
