package com.glr.deenwallet.waec;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "waec_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaecAudit {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", length = 254)
    private String actorEmail;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
