package com.glr.deenwallet.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserProfileUpdateRequest(
        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 120, message = "Full name must be between 2 and 120 characters")
        @Pattern(regexp = "^[^<>]*$", message = "Full name cannot contain '<' or '>'")
        String fullName,
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = "^[^<>]*$", message = "Username cannot contain '<' or '>'")
        String username
) {}
