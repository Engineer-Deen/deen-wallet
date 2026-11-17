package com.glr.deenwallet.pawapay;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A person a customer sends money abroad to, saved so the customer does not have to type the details again. */
@Entity
@Table(name = "cross_border_recipients")
@Getter
@Setter
@NoArgsConstructor
public class CrossBorderRecipient {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 3)
    private String country;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "first_name", nullable = false, length = 64)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 64)
    private String lastName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
