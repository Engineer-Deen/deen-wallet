package com.glr.deenwallet.crypto;

import com.glr.deenwallet.audit.AuditService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * "Crypto Orders" admin section: every "send to a crypto wallet" request with its customer, so a problem can be traced from a
 * reference, a customer, a wallet address or the provider's transaction id. An admin can ask the provider for the latest status
 * of a waiting order. DeenWallet never holds the money or the crypto, so there is nothing to refund or resend from here.
 * /api/admin/** is already limited to ADMIN / SUPER_ADMIN; an admin can also be denied this section by a super admin.
 */
@RestController
@RequestMapping("/api/admin/crypto")
public class AdminCryptoController {

    private static final String COLUMNS =
            "o.id, o.reference, o.amount_minor, o.operator, o.phone, o.asset, o.chain, o.address, o.provider_tx_id, o.status, "
                    + "o.crypto_amount, o.chain_tx_id, o.failure_reason, "
                    + "CAST(EXTRACT(EPOCH FROM o.created_at) * 1000 AS bigint), CAST(EXTRACT(EPOCH FROM o.updated_at) * 1000 AS bigint), "
                    + "CAST(EXTRACT(EPOCH FROM o.completed_at) * 1000 AS bigint), "
                    + "o.user_id, u.full_name, u.email, u.account_number";

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final AuditService audit;
    private final CryptoOrderService orders;

    public AdminCryptoController(UserRepository users, AuditService audit, CryptoOrderService orders) {
        this.users = users;
        this.audit = audit;
        this.orders = orders;
    }

    @GetMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) String q,
                                                          @RequestParam(required = false) String status) {
        admin();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.length() > 80) needle = needle.substring(0, 80);
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM crypto_orders o JOIN users u ON u.id = o.user_id WHERE 1 = 1");
        if (!needle.isEmpty()) {
            sql.append(" AND (lower(o.reference) LIKE :like OR lower(o.address) LIKE :like OR lower(COALESCE(o.provider_tx_id, '')) LIKE :like "
                    + "OR lower(COALESCE(o.chain_tx_id, '')) LIKE :like OR lower(u.email) LIKE :like OR lower(u.full_name) LIKE :like "
                    + "OR lower(u.account_number) LIKE :like OR o.phone LIKE :like)");
        }
        if ("OPEN".equals(st)) {
            sql.append(" AND o.status IN ('CREATED', 'PENDING')");
        } else if (st.equals("SUCCESSFUL") || st.equals("FAILED")) {
            sql.append(" AND o.status = '").append(st).append("'");   // st is one of two fixed words
        }
        sql.append(" ORDER BY o.created_at DESC LIMIT 300");
        jakarta.persistence.Query query = em.createNativeQuery(sql.toString());
        if (!needle.isEmpty()) query.setParameter("like", "%" + needle.replace("%", "").replace("_", "") + "%");
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(view(r));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
        admin();
        return ResponseEntity.ok(load(id));
    }

    /** Ask the provider for the latest status of an order. The provider's answer decides; nothing is forced from here. */
    @PostMapping("/{id}/check")
    public ResponseEntity<Map<String, Object>> check(@PathVariable UUID id) {
        User admin = admin();
        Map<String, Object> before = load(id);
        orders.refreshFromProvider(id);
        Map<String, Object> after = load(id);
        audit.record(admin, "CRYPTO", "CRYPTO_CHECK", "Checked a crypto order with the provider", id, String.valueOf(before.get("reference")),
                "Order " + before.get("reference") + " of " + before.get("ownerEmail") + ": " + before.get("status") + " -> " + after.get("status"), null);
        return ResponseEntity.ok(after);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(UUID id) {
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS
                        + " FROM crypto_orders o JOIN users u ON u.id = o.user_id WHERE o.id = :id")
                .setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Crypto order not found.");
        return view(rows.get(0));
    }

    private static Map<String, Object> view(Object[] r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r[0]));
        m.put("reference", r[1]);
        m.put("amountMinor", ((Number) r[2]).longValue());
        m.put("operator", r[3]);
        m.put("phone", r[4]);
        m.put("asset", r[5]);
        m.put("chain", r[6]);
        m.put("address", r[7]);
        m.put("providerTxId", r[8]);
        m.put("status", r[9]);
        m.put("cryptoAmount", r[10]);
        m.put("chainTxId", r[11]);
        m.put("failureReason", r[12]);
        m.put("createdAt", ((Number) r[13]).longValue());
        m.put("updatedAt", ((Number) r[14]).longValue());
        m.put("completedAt", r[15] == null ? null : ((Number) r[15]).longValue());
        m.put("ownerId", String.valueOf(r[16]));
        m.put("ownerName", r[17]);
        m.put("ownerEmail", r[18]);
        m.put("ownerAccount", r[19]);
        return m;
    }

    private User admin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = users.findById(UUID.fromString((String) a.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        if (!"ADMIN".equals(u.getRole()) && !"SUPER_ADMIN".equals(u.getRole())) throw new SecurityException("Access denied: admin only");
        if (!u.isActive()) throw new SecurityException("Admin account is inactive.");
        return u;
    }
}
