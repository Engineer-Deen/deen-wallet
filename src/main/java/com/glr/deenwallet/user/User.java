package com.glr.deenwallet.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue private UUID id;
    @Column(name="full_name", nullable=false) private String fullName;
    @Column(nullable=false, unique=true) private String username;
    @Column(nullable=false, unique=true) private String phone;
    @Column(nullable=false, unique=true) private String email;
    @Column(name="active", nullable=false) private boolean active;
    @Column(name="locked", nullable=false) private boolean locked;
    @Column(name="pin_attempts") private int pinAttempts;
    @Column(name="locked_at", columnDefinition="TIMESTAMPTZ") private Instant lockedAt;
    @Column(name="locked_by") private UUID lockedBy;
    @Column(name="password_hash", nullable=false) private String passwordHash;
    @Column(name="role") private String role;
    @Column(name="verification_code") private String verificationCode;
    @Column(name="pin_hash", nullable=false) private String pinHash;
    @Column(name="account_number", nullable=false, unique=true) private String accountNumber;
    @Column(name="email_verified", nullable=false) private boolean emailVerified;
    @Column(name="phone_verified", nullable=false) private boolean phoneVerified;

    @Column(name="login_failed_attempts", nullable=false)
    private int loginFailedAttempts;

    @Column(name="login_locked_until")
    private Instant loginLockedUntil;

    @Column(name="last_login_failed_at")
    private Instant lastLoginFailedAt;

    @Column(name="admin_recovery_required", nullable=false)
    private boolean adminRecoveryRequired;

    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        active = true;
        locked = false;
        pinAttempts = 0;
        emailVerified = false;
        phoneVerified = false;
        loginFailedAttempts = 0;
        adminRecoveryRequired = false;
        if (role == null) role = "USER";
        if (pinHash == null) pinHash = "";
    }

    @PreUpdate protected void onUpdate() { updatedAt = Instant.now(); }

    public String getFirstName() {
        if (fullName == null || fullName.isBlank()) return "";
        return fullName.trim().split("\\s+")[0];
    }
}
