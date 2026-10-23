package com.glr.deenwallet.kyc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One encrypted photo (front / back / selfie) of a submission. {@code data} is AES-GCM ciphertext. */
@Entity
@Table(name = "kyc_documents")
public class KycDocument {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private KycDocumentKind kind;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KycDocument() { }

    public KycDocument(UUID submissionId, KycDocumentKind kind, String contentType, int sizeBytes, byte[] data) {
        this.submissionId = submissionId;
        this.kind = kind;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.data = data;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public KycDocumentKind getKind() { return kind; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(int sizeBytes) { this.sizeBytes = sizeBytes; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
}
