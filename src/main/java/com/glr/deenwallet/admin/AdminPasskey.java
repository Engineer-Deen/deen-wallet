package com.glr.deenwallet.admin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One passkey of an admin. Only the public key is stored (base64 of the X.509 form). */
@Entity
@Table(name = "admin_passkeys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPasskey {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "credential_id", nullable = false, unique = true, length = 1400)
    private String credentialId;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "alg", nullable = false)
    private int alg;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
