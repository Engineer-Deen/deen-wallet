package com.glr.deenwallet.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="biometric_credentials", indexes={
        @Index(name="idx_biometric_credentials_user_id", columnList="user_id"),
        @Index(name="idx_biometric_credentials_credential_id", columnList="credential_id")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BiometricCredential {
    @Id @GeneratedValue private UUID id;
    @Column(name="user_id", nullable=false) private UUID userId;
    @Column(name="credential_id", nullable=false, unique=true, length=200) private String credentialId;
    @Column(name="public_key", nullable=false, columnDefinition="TEXT") private String publicKey;
    @Column(name="device_name", length=150) private String deviceName;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="last_used_at") private Instant lastUsedAt;
    @Column(name="revoked", nullable=false) private boolean revoked;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
