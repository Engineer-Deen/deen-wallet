package com.glr.deenwallet.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    /** Cloudflare "Verify you are human" result; only checked when Turnstile is switched on. */
    private String turnstileToken;
}
