package com.glr.deenwallet.auth;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="refresh_tokens", indexes=@Index(name="idx_refresh_tokens_user_id", columnList="user_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RefreshToken {
    @Id @GeneratedValue private UUID id;
    @Column(nullable=false, unique=true, length=100) private String jti;
    @Column(name="user_id", nullable=false) private UUID userId;
    @Column(name="expires_at", nullable=false) private Instant expiresAt;
    @Column(nullable=false) private boolean revoked;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @PrePersist void onCreate(){ if(createdAt==null) createdAt=Instant.now(); }
}

