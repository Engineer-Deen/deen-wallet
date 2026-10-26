package com.glr.deenwallet.wallet;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin Wallet screen. /api/admin/** is already limited to ADMIN / SUPER_ADMIN by SecurityConfig;
 * the super-admin-only parts are checked again here.
 * Every rule violation answers 400 (never 401/403, which the admin app treats as "session expired").
 */
@RestController
@RequestMapping("/api/admin/wallet")
public class WalletController {

    public record TransferRequest(UUID adminId, BigDecimal amount, String note, String pin) { }

    public record WithdrawRequest(String method, String providerId, String destination, BigDecimal amount, String pin, Boolean save) { }

    public record SavedRequest(String method, String providerId, String destination) { }

    private final WalletService wallet;
    private final UserRepository users;

    public WalletController(WalletService wallet, UserRepository users) {
        this.wallet = wallet;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<WalletService.Summary> summary() {
        return ResponseEntity.ok(wallet.summary(admin()));
    }

    /** Live payments in / payouts out of the DeenWallet account. type = credit (payments in) or debit (payouts out). Super admin only. */
    @GetMapping("/transactions")
    public ResponseEntity<WalletService.TxPage> transactions(@RequestParam(required = false) String type,
                                                             @RequestParam(required = false) String after,
                                                             @RequestParam(required = false) String q) {
        requireSuper(admin());
        if (q != null && !q.isBlank()) return ResponseEntity.ok(wallet.search(type, q));
        return ResponseEntity.ok(wallet.transactions(type, after));
    }

    @GetMapping("/activity")
    public ResponseEntity<List<WalletService.Activity>> activity() {
        return ResponseEntity.ok(wallet.activity(admin()));
    }

    @GetMapping("/admins")
    public ResponseEntity<List<WalletService.AdminRow>> admins() {
        requireSuper(admin());
        return ResponseEntity.ok(wallet.admins());
    }

    @GetMapping("/options")
    public ResponseEntity<WalletService.Options> options() {
        admin();
        return ResponseEntity.ok(wallet.options());
    }

    @GetMapping("/lookup")
    public ResponseEntity<WalletService.Lookup> lookup(@RequestParam String method,
                                                       @RequestParam String providerId,
                                                       @RequestParam String destination) {
        return ResponseEntity.ok(wallet.lookup(admin(), method, providerId, destination));
    }

    @PostMapping("/transfer")
    public ResponseEntity<WalletService.Summary> transfer(@RequestBody TransferRequest r) {
        User me = admin();
        requireSuper(me);
        return ResponseEntity.ok(wallet.transfer(me, r.adminId(), r.amount(), r.note(), r.pin()));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<WalletService.Summary> withdraw(@RequestBody WithdrawRequest r) {
        return ResponseEntity.ok(wallet.withdraw(admin(), r.method(), r.providerId(), r.destination(), r.amount(), r.pin(),
                Boolean.TRUE.equals(r.save())));
    }

    /** Mobile money number (8 digits after +232) -> network and name on the number. */
    @GetMapping("/detect")
    public ResponseEntity<WalletService.Detect> detect(@RequestParam String phone) {
        return ResponseEntity.ok(wallet.detect(admin(), phone));
    }

    @GetMapping("/saved")
    public ResponseEntity<List<WalletService.Saved>> saved() {
        return ResponseEntity.ok(wallet.saved(admin()));
    }

    @PostMapping("/saved")
    public ResponseEntity<WalletService.Saved> addSaved(@RequestBody SavedRequest r) {
        return ResponseEntity.ok(wallet.addSaved(admin(), r.method(), r.providerId(), r.destination()));
    }

    @DeleteMapping("/saved/{id}")
    public ResponseEntity<Void> deleteSaved(@PathVariable UUID id) {
        wallet.deleteSaved(admin(), id);
        return ResponseEntity.noContent().build();
    }

    private void requireSuper(User u) {
        if (!WalletService.isSuper(u)) throw new IllegalArgumentException("Only a super admin can do this.");
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
