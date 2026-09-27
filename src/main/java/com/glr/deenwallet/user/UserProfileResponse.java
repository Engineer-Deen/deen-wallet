package com.glr.deenwallet.user;

import java.time.Instant;

public record UserProfileResponse(
        String fullName,
        String firstName,
        String username,
        String phone,
        String email,
        String accountNumber,
        boolean emailVerified,
        boolean active,
        boolean locked,
        String role,
        boolean pinSet,
        int loginFailedAttempts,
        Instant loginLockedUntil
) {}
