package com.glr.deenwallet.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must be at most 100 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Full name cannot contain '<' or '>'")
    private String fullName;

    @NotBlank(message = "Username is required")
    @Size(max = 40, message = "Username must be at most 40 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Username cannot contain '<' or '>'")
    private String username;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX")
    private String phone;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    // ==============================================================
    // PIN FIELD — was validating against the phone number pattern
    // (^\+232\d{8}$), which a 4-digit PIN can never match. Fixed to
    // actually validate a 4-digit PIN.
    // ==============================================================
    @NotBlank(message = "PIN is required")
    @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
    private String pin;
}
