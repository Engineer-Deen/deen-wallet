package com.glr.deenwallet.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="biometric_challenges", indexes={
        @Index(name="idx_biometric_challenges_challenge", columnList="challenge"),
        @Index(name="idx_biometric_challenges_expires_at", columnList="expires_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class BiometricChallenge {
    @Id @GeneratedValue private UUID id;
    @Column(name="user_id") private UUID userId;
    @Column(name="credential_id", length=200) private String credentialId;
    @Column(nullable=false, unique=true, length=200) private String challenge;
    @Column(nullable=false, length=30) private String purpose;
    @Column(name="expires_at", nullable=false) private Instant expiresAt;
    @Column(nullable=false) private boolean used;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
