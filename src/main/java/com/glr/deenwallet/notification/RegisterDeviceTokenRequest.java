package com.glr.deenwallet.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterDeviceTokenRequest(
        @NotBlank(message = "fcmToken is required")
        @Size(max = 4096, message = "fcmToken is too long")
        String fcmToken,

        @NotBlank(message = "platform is required")
        @Pattern(regexp = "^(android|ios|web)$", message = "platform must be android, ios, or web")
        String platform
) {}
