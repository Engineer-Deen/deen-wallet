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
    /** Set only when a refresh ROTATED this token (not on logout/password reset). Used for the reuse grace window. */
    @Column(name="revoked_at") private Instant revokedAt;
    /** One login = one session. It stays the same while the refresh token rotates (shown as a "trusted device"). */
    @Column(name="session_id") private UUID sessionId;
    /** The id the app generates for itself (X-Device-Id header). Lets a new login replace that device's older one. */
    @Column(name="device_key", length=64) private String deviceKey;
    @Column(name="device_name", length=120) private String deviceName;
    @Column(name="device_platform", length=30) private String devicePlatform;
    @Column(name="last_ip", length=64) private String lastIp;
    @Column(name="last_used_at") private Instant lastUsedAt;
    @Column(name="first_login_at") private Instant firstLoginAt;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @PrePersist void onCreate(){
        if(createdAt==null) createdAt=Instant.now();
        if(sessionId==null) sessionId=UUID.randomUUID();
    }
}