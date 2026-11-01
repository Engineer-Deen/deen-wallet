package com.glr.deenwallet.safety;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** The single row that says whether money movement is frozen. */
@Entity
@Table(name = "money_safety")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneySafety {

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "frozen", nullable = false)
    private boolean frozen;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
}
