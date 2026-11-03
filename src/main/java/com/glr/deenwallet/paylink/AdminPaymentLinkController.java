package com.glr.deenwallet.paylink;

import com.glr.deenwallet.audit.AuditService;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
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
 * "Payment Links" admin section: every payment link with its owner and every payment made through it, so a problem
 * can be traced from a link code, a payment reference, a payer or an owner. Admins can close a link that is being abused.
 * /api/admin/** is already limited to ADMIN / SUPER_ADMIN; an admin can also be denied this section by a super admin.
 */
@RestController
@RequestMapping("/api/admin/payment-links")
public class AdminPaymentLinkController {

    private static final String EFFECTIVE_STATUS =
            "CASE WHEN r.status = 'CLOSED' THEN 'CLOSED' "
                    + "WHEN r.expires_at IS NOT NULL AND r.expires_at < now() THEN 'EXPIRED' "
                    + "WHEN NOT r.reusable AND EXISTS (SELECT 1 FROM transactions x WHERE x.payment_request_id = r.id AND x.status = 'COMPLETED') THEN 'PAID' "
                    + "ELSE 'ACTIVE' END";

    private static final String COLUMNS =
            "r.id, r.slug, r.title, r.note, r.amount_value, r.reusable, r.dest_type, r.dest_provider_id, r.dest_account, "
                    + "r.dest_holder_name, r.dest_bank_name, " + EFFECTIVE_STATUS + ", "
                    + "CAST(EXTRACT(EPOCH FROM r.expires_at) * 1000 AS bigint), CAST(EXTRACT(EPOCH FROM r.created_at) * 1000 AS bigint), "
                    + "r.owner_user_id, u.full_name, u.email, u.account_number, "
                    + "(SELECT COUNT(*) FROM transactions t WHERE t.payment_request_id = r.id AND t.status = 'COMPLETED'), "
                    + "(SELECT COALESCE(SUM(t.amount_value), 0) FROM transactions t WHERE t.payment_request_id = r.id AND t.status = 'COMPLETED'), "
                    + "(SELECT COUNT(*) FROM transactions t WHERE t.payment_request_id = r.id AND t.status IN ('PAID_IN', 'PAYING_OUT')), "
                    + "(SELECT COUNT(*) FROM transactions t WHERE t.payment_request_id = r.id AND t.status IN ('PAYOUT_FAILED', 'REFUND_PENDING', 'NEEDS_REVIEW')), "
                    + "CAST(EXTRACT(EPOCH FROM r.closed_at) * 1000 AS bigint)";

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final TransactionTemplate tx;

    public AdminPaymentLinkController(UserRepository users, AuditService audit, NotificationService notifications,
                                      TransactionTemplate tx) {
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
        this.tx = tx;
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
                + " FROM payment_requests r JOIN users u ON u.id = r.owner_user_id WHERE 1 = 1");
        if (!needle.isEmpty()) {
            sql.append(" AND (lower(r.slug) LIKE :like OR lower(r.title) LIKE :like OR lower(u.email) LIKE :like "
                    + "OR lower(u.full_name) LIKE :like OR lower(u.account_number) LIKE :like "
                    + "OR EXISTS (SELECT 1 FROM transactions x WHERE x.payment_request_id = r.id AND lower(x.transaction_code) LIKE :like))");
        }
        if ("PROBLEMS".equals(st)) {
            sql.append(" AND EXISTS (SELECT 1 FROM transactions p WHERE p.payment_request_id = r.id "
                    + "AND p.status IN ('PAYOUT_FAILED', 'REFUND_PENDING', 'NEEDS_REVIEW'))");
        } else if (st.equals("ACTIVE") || st.equals("PAID") || st.equals("CLOSED") || st.equals("EXPIRED")) {
            sql.append(" AND (").append(EFFECTIVE_STATUS).append(") = '").append(st).append("'");   // st is one of four fixed words
        }
        sql.append(" ORDER BY r.created_at DESC LIMIT 300");
        jakarta.persistence.Query query = em.createNativeQuery(sql.toString());
        if (!needle.isEmpty()) query.setParameter("like", "%" + needle.replace("%", "").replace("_", "") + "%");
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(view(r));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
        admin();
        Map<String, Object> v = load(id);
        List<Object[]> rows = em.createNativeQuery(
                "SELECT t.id, t.transaction_code, t.status, t.amount_value, t.total_charged_value, t.payer_name, t.source_provider_id, "
                        + "t.source_phone, t.failure_reason, CAST(EXTRACT(EPOCH FROM t.created_at) * 1000 AS bigint) "
                        + "FROM transactions t WHERE t.payment_request_id = :r ORDER BY t.created_at DESC LIMIT 200")
                .setParameter("r", id).getResultList();
        List<Map<String, Object>> payments = new ArrayList<>();
        for (Object[] x : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(x[0]));
            m.put("reference", x[1]);
            m.put("status", x[2]);
            m.put("amountMinor", x[3] == null ? 0L : ((Number) x[3]).longValue());
            m.put("totalMinor", x[4] == null ? 0L : ((Number) x[4]).longValue());
            m.put("payerName", x[5]);
            m.put("payerProvider", x[6]);
            m.put("payerPhone", x[7]);
            m.put("failureReason", x[8]);
            m.put("createdAt", ((Number) x[9]).longValue());
            payments.add(m);
        }
        v.put("payments", payments);
        return ResponseEntity.ok(v);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<Map<String, Object>> close(@PathVariable UUID id) {
        User admin = admin();
        Map<String, Object> before = load(id);
        if (!"ACTIVE".equals(before.get("status"))) throw new IllegalStateException("This link is already " + String.valueOf(before.get("status")).toLowerCase(Locale.ROOT) + ".");
        tx.executeWithoutResult(st -> em.createNativeQuery(
                "UPDATE payment_requests SET status = 'CLOSED', closed_at = now(), updated_at = now() WHERE id = :id AND status = 'ACTIVE'")
                .setParameter("id", id).executeUpdate());
        UUID owner = UUID.fromString(String.valueOf(before.get("ownerId")));
        audit.record(admin, "LINKS", "LINK_CLOSE", "Closed a payment link", id, String.valueOf(before.get("title")),
                "Link " + before.get("slug") + " owned by " + before.get("ownerEmail"), null);
        try {
            notifications.create(owner, "PAYMENT_LINK_CLOSED", "Payment link closed",
                    "Your payment link \"" + before.get("title") + "\" was closed by DeenWallet support. Contact support if you think this is a mistake.", null, null);
        } catch (RuntimeException ignored) {
            // the link is closed either way
        }
        return ResponseEntity.ok(load(id));
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(UUID id) {
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS
                        + " FROM payment_requests r JOIN users u ON u.id = r.owner_user_id WHERE r.id = :id")
                .setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Payment link not found.");
        return view(rows.get(0));
    }

    private static Map<String, Object> view(Object[] r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r[0]));
        m.put("slug", r[1]);
        m.put("title", r[2]);
        m.put("note", r[3]);
        m.put("amountMinor", r[4] == null ? null : ((Number) r[4]).longValue());
        m.put("reusable", r[5]);
        m.put("destType", r[6]);
        m.put("destProviderId", r[7]);
        m.put("destAccount", r[8]);
        m.put("destHolderName", r[9]);
        m.put("destBankName", r[10]);
        m.put("status", r[11]);
        m.put("expiresAt", r[12] == null ? null : ((Number) r[12]).longValue());
        m.put("createdAt", ((Number) r[13]).longValue());
        m.put("ownerId", String.valueOf(r[14]));
        m.put("ownerName", r[15]);
        m.put("ownerEmail", r[16]);
        m.put("ownerAccount", r[17]);
        m.put("paidCount", ((Number) r[18]).longValue());
        m.put("receivedMinor", ((Number) r[19]).longValue());
        m.put("inProgress", ((Number) r[20]).longValue());
        m.put("problems", ((Number) r[21]).longValue());
        m.put("closedAt", r[22] == null ? null : ((Number) r[22]).longValue());
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
