package com.glr.deenwallet.pawapay;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** The address and nationality a customer typed once, kept encrypted so they do not have to type them again. */
@Entity
@Table(name = "cross_border_sender_profiles")
@Getter
@Setter
@NoArgsConstructor
public class CrossBorderSenderProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "data_enc", nullable = false, columnDefinition = "TEXT")
    private String dataEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
