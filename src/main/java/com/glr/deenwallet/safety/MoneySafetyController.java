package com.glr.deenwallet.safety;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Emergency freeze. Any admin can FREEZE (to stop a break-in fast); only a super admin can RESUME.
 * Both need the admin's PIN. /api/admin/** is already limited to admins by SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/safety")
@RequiredArgsConstructor
public class MoneySafetyController {

    public record FreezeRequest(Boolean frozen, String pin, String reason) { }

    private final MoneySafetyService safety;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/freeze")
    public ResponseEntity<MoneySafetyService.Status> status() {
        admin();
        return ResponseEntity.ok(safety.status());
    }

    @PostMapping("/freeze")
    public ResponseEntity<MoneySafetyService.Status> set(@RequestBody FreezeRequest r) {
        User me = admin();
        boolean freeze = Boolean.TRUE.equals(r.frozen());
        if (!freeze && !"SUPER_ADMIN".equals(me.getRole())) {
            throw new IllegalArgumentException("Only a super admin can resume money movement.");
        }
        verifyPin(me, r.pin());
        return ResponseEntity.ok(safety.setFrozen(me, freeze, r.reason()));
    }

    private void verifyPin(User me, String pin) {
        if (pin == null || !pin.matches("^\\d{4}$")) throw new IllegalArgumentException("Enter your 4-digit PIN.");
        User fresh = users.findById(me.getId()).orElseThrow(() -> new IllegalArgumentException("Admin account not found."));
        if (fresh.getPinHash() == null || fresh.getPinHash().isEmpty()) {
            throw new IllegalArgumentException("Set your admin PIN first (Menu > Security).");
        }
        if (passwordEncoder.matches(pin, fresh.getPinHash())) {
            users.clearFailedPinAttempts(me.getId());
            return;
        }
        com.glr.deenwallet.monitoring.SecurityTag.tag("PIN_FAILED");
        users.recordFailedPinAttempt(me.getId(), Instant.now());
        User state = users.findById(me.getId()).orElse(fresh);
        if (state.getPinAttempts() >= 2) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PIN is disabled. Please log in again.");
        }
        throw new IllegalArgumentException("Wrong PIN. Please try again.");
    }

    private User admin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = users.findById(UUID.fromString((String) a.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        if (!"ADMIN".equals(u.getRole()) && !"SUPER_ADMIN".equals(u.getRole()))
            throw new SecurityException("Access denied: admin only");
        if (!u.isActive()) throw new SecurityException("Admin account is inactive.");
        return u;
    }
}
