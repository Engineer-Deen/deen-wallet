package com.glr.deenwallet.waec;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "waec_pins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaecPin {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pin_encrypted", nullable = false, length = 600)
    private String pinEncrypted;

    @Column(name = "serial_encrypted", nullable = false, length = 600)
    private String serialEncrypted;

    @Column(name = "pin_hash", nullable = false, length = 64)
    private String pinHash;

    @Column(name = "serial_hash", nullable = false, length = 64)
    private String serialHash;

    @Column(name = "pin_last4", nullable = false, length = 4)
    private String pinLast4;

    @Column(name = "serial_last4", nullable = false, length = 4)
    private String serialLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private WaecPinStatus status;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "reserved_at")
    private Instant reservedAt;

    @Column(name = "sold_at")
    private Instant soldAt;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_by_email", length = 254)
    private String addedByEmail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = WaecPinStatus.AVAILABLE;
    }
}
