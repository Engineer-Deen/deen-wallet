package com.glr.deenwallet.user;

import com.glr.deenwallet.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository repo;
    private final AuthService authService;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me() {
        User u = current();
        return ResponseEntity.ok(toResponse(u));
    }

    @PutMapping("/me/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @Valid @RequestBody UserProfileUpdateRequest request
    ) {
        User user = current();
        String username = request.username().trim();

        repo.findByUsername(username).ifPresent(existing -> {
            if (!existing.getId().equals(user.getId())) {
                throw new IllegalArgumentException("Username already taken");
            }
        });

        user.setFullName(request.fullName().trim());
        user.setUsername(username);
        repo.save(user);

        log.info("Profile updated for user {}", user.getId());
        return ResponseEntity.ok(toResponse(user));
    }

    @PostMapping("/me/pin")
    public ResponseEntity<Void> setPin(@Valid @RequestBody SetPinRequest request) {
        authService.setPin(current().getId(), request.pin(), request.currentPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/verify-pin")
    public ResponseEntity<Void> verifyPin(@Valid @RequestBody PinRequest request) {
        authService.verifyPin(current().getId(), request.pin());
        return ResponseEntity.noContent().build();
    }

    private UserProfileResponse toResponse(User u) {
        return new UserProfileResponse(
                u.getFullName(),
                u.getFirstName(),
                u.getUsername(),
                u.getPhone(),
                u.getEmail(),
                u.getAccountNumber(),
                u.isEmailVerified(),
                u.isActive(),
                u.isLocked(),
                u.getRole(),
                u.getPinHash() != null && !u.getPinHash().isBlank(),
                u.getLoginFailedAttempts(),
                u.getLoginLockedUntil()
        );
    }

    private User current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new SecurityException("Not authenticated");
        }

        return repo.findById(UUID.fromString((String) authentication.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
    }

    public record PinRequest(
            @NotBlank(message = "PIN is required")
            @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
            String pin
    ) {}

    public record SetPinRequest(
            @NotBlank(message = "PIN is required")
            @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
            String pin,
            @NotBlank(message = "Current password is required")
            String currentPassword
    ) {}
}
