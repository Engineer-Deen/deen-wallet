package com.glr.deenwallet.pawapay;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pawapay_events")
@Getter
@Setter
@NoArgsConstructor
public class PawaPayEvent {
    @Id
    @GeneratedValue
    private UUID id;
    @Column(name = "event_key", nullable = false, unique = true, length = 128)
    private String eventKey;
    @Column(nullable = false, length = 16)
    private String kind;
    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;
    @Column(length = 24)
    private String claimed;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
