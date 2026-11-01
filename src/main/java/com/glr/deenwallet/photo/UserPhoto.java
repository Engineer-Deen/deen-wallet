package com.glr.deenwallet.photo;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** The profile photo of one account: a small square JPEG. */
@Entity
@Table(name = "user_photos")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPhoto {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
