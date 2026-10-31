package com.glr.deenwallet.admin;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The signed-in admin manages their own passkeys (max 5). Adding or deleting needs the account password. */
@RestController
@RequestMapping("/api/admin/passkeys")
@RequiredArgsConstructor
public class AdminPasskeyController {

    private final AdminPasskeyService service;
    private final UserRepository users;

    @GetMapping
    public ResponseEntity<PasskeyList> list() {
        User me = me();
        List<Item> items = service.list(me.getId()).stream().map(Item::from).toList();
        return ResponseEntity.ok(new PasskeyList(items, AdminPasskeyService.MAX_PASSKEYS));
    }

    @PostMapping("/register/options")
    public ResponseEntity<Map<String, Object>> registerOptions(@Valid @RequestBody PasswordRequest r) {
        return ResponseEntity.ok(service.registrationOptions(me(), r.password));
    }

    @PostMapping("/register/finish")
    public ResponseEntity<Item> registerFinish(@Valid @RequestBody FinishRequest r) {
        return ResponseEntity.ok(Item.from(
                service.finishRegistration(me(), r.challengeId, r.label, r.clientDataJSON, r.attestationObject)));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @Valid @RequestBody PasswordRequest r) {
        service.delete(me(), id, r.password);
        return ResponseEntity.noContent().build();
    }

    /** The app is locked: step 1, a one-time challenge for the signed-in admin's own passkeys. */
    @PostMapping("/unlock/options")
    public ResponseEntity<Map<String, Object>> unlockOptions() {
        return ResponseEntity.ok(service.unlockOptions(me()));
    }

    /** Step 2: the signed answer. Success = 204; the page then closes the lock screen. */
    @PostMapping("/unlock/verify")
    public ResponseEntity<Void> unlockVerify(@Valid @RequestBody UnlockRequest r) {
        service.verifyUnlock(me(), r.challengeId, r.id, r.clientDataJSON, r.authenticatorData, r.signature);
        return ResponseEntity.noContent().build();
    }

    private User me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = users.findById(UUID.fromString((String) auth.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        if (!"ADMIN".equals(u.getRole()) && !"SUPER_ADMIN".equals(u.getRole())) {
            throw new SecurityException("Access denied: admin only");
        }
        return u;
    }

    @Getter @Setter
    public static class UnlockRequest {
        @NotBlank String challengeId;
        @NotBlank String id;
        @NotBlank String clientDataJSON;
        @NotBlank String authenticatorData;
        @NotBlank String signature;
    }

    @Getter @Setter
    public static class PasswordRequest {
        @NotBlank String password;
    }

    @Getter @Setter
    public static class FinishRequest {
        @NotBlank String challengeId;
        String label;
        @NotBlank String clientDataJSON;
        @NotBlank String attestationObject;
    }

    public record Item(String id, String label, Instant createdAt, Instant lastUsedAt) {
        static Item from(AdminPasskey p) {
            return new Item(p.getId().toString(), p.getLabel(), p.getCreatedAt(), p.getLastUsedAt());
        }
    }

    public record PasskeyList(List<Item> items, int max) {}
}
