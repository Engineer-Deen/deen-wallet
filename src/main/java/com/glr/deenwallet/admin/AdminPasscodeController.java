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
import java.util.UUID;

/** The signed-in admin manages their own recovery passcodes (max 5). Needs the account password to add or delete. */
@RestController
@RequestMapping("/api/admin/passcodes")
@RequiredArgsConstructor
public class AdminPasscodeController {

    private final AdminPasscodeService service;
    private final UserRepository users;

    @GetMapping
    public ResponseEntity<PasscodeList> list() {
        User me = me();
        List<Item> items = service.list(me.getId()).stream().map(Item::from).toList();
        return ResponseEntity.ok(new PasscodeList(items, AdminPasscodeService.MAX_PASSCODES));
    }

    @PostMapping
    public ResponseEntity<Item> add(@Valid @RequestBody AddRequest r) {
        User me = me();
        return ResponseEntity.ok(Item.from(service.add(me, r.label, r.passcode, r.password)));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestBody DeleteRequest r) {
        User me = me();
        service.delete(me, id, r.password);
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
    public static class AddRequest {
        String label;
        @NotBlank String passcode;
        @NotBlank String password;
    }

    @Getter @Setter
    public static class DeleteRequest {
        @NotBlank String password;
    }

    public record Item(String id, String label, Instant createdAt, Instant lastUsedAt) {
        static Item from(AdminPasscode p) {
            return new Item(p.getId().toString(), p.getLabel(), p.getCreatedAt(), p.getLastUsedAt());
        }
    }

    public record PasscodeList(List<Item> items, int max) {}
}
