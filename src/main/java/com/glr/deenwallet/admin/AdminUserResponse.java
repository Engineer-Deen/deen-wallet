package com.glr.deenwallet.admin;

import com.glr.deenwallet.user.User;

import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(
        UUID id,
        String fullName,
        String username,
        String phone,
        String email,
        String accountNumber,
        boolean emailVerified,
        boolean phoneVerified,
        boolean active,
        boolean locked,
        int pinAttempts,
        int loginFailedAttempts,
        boolean adminRecoveryRequired,
        Instant loginLockedUntil,
        Instant createdAt,
        String role) {

    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(
                u.getId(), u.getFullName(), u.getUsername(), u.getPhone(), u.getEmail(),
                u.getAccountNumber(), u.isEmailVerified(), u.isPhoneVerified(), u.isActive(),
                u.isLocked(), u.getPinAttempts(), u.getLoginFailedAttempts(),
                u.isAdminRecoveryRequired(), u.getLoginLockedUntil(), u.getCreatedAt(), u.getRole());
    }
}
