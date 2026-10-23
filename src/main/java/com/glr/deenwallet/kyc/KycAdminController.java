package com.glr.deenwallet.kyc;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin "KYC" section. /api/admin/** is already limited to ADMIN / SUPER_ADMIN; the role is checked again here. */
@RestController
@RequestMapping("/api/admin/kyc")
public class KycAdminController {

    private final KycService kyc;
    private final UserRepository users;

    public KycAdminController(KycService kyc, UserRepository users) {
        this.kyc = kyc;
        this.users = users;
    }

    @GetMapping("/summary")
    public ResponseEntity<KycDtos.AdminSummary> summary() {
        admin();
        return ResponseEntity.ok(kyc.summary());
    }

    @GetMapping
    public ResponseEntity<List<KycDtos.AdminCard>> list(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String q) {
        admin();
        return ResponseEntity.ok(kyc.list(status, q));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KycDtos.AdminDetail> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(kyc.detail(id, admin()));
    }

    @GetMapping("/{id}/documents/{kind}")
    public ResponseEntity<KycDtos.AdminPhoto> photo(@PathVariable UUID id, @PathVariable String kind) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(kyc.photo(id, kind, admin()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<KycDtos.AdminDetail> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(kyc.approve(id, admin()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<KycDtos.AdminDetail> reject(@PathVariable UUID id,
                                                      @RequestBody KycDtos.DecisionRequest body) {
        return ResponseEntity.ok(kyc.reject(id, body == null ? null : body.reason(), admin()));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<KycDtos.AdminDetail> revoke(@PathVariable UUID id,
                                                      @RequestBody KycDtos.DecisionRequest body) {
        return ResponseEntity.ok(kyc.revoke(id, body == null ? null : body.reason(), admin()));
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
