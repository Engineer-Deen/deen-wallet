package com.glr.deenwallet.admin;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One recovery passcode of an admin. Only a hash is stored. */
@Entity
@Table(name = "admin_passcodes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPasscode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "label", nullable = false, length = 40)
    private String label;

    @Column(name = "passcode_hash", nullable = false, length = 100)
    private String passcodeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
