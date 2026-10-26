package com.glr.deenwallet.audit;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** Audit tab. /api/admin/** is already limited to ADMIN / SUPER_ADMIN; the role is checked again here. */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private final AuditService audit;
    private final UserRepository users;

    public AuditController(AuditService audit, UserRepository users) {
        this.audit = audit;
        this.users = users;
    }

    /** category: USERS, KYC, WAEC, ADMINS, WALLET, MESSAGING, PAYOUTS, SECURITY, SYSTEM. before: "at" of the last row you have. */
    @GetMapping
    public ResponseEntity<List<AuditService.Entry>> list(@RequestParam(required = false) String category,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String before,
                                                         @RequestParam(required = false, defaultValue = "30") int limit) {
        User me = admin();
        if (!"SUPER_ADMIN".equals(me.getRole())) throw new IllegalArgumentException("Only the super admin can view the audit log.");
        Instant cursor = null;
        if (before != null && !before.isBlank()) {
            try {
                cursor = Instant.parse(before.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date.");
            }
        }
        return ResponseEntity.ok(audit.list(me, "SUPER_ADMIN".equals(me.getRole()), category, q, cursor, limit));
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
