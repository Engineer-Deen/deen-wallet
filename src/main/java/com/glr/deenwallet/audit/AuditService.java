package com.glr.deenwallet.audit;

import com.glr.deenwallet.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin audit log. Writing a line never throws (an audit problem must not break the action being audited).
 * Reading: the Audit tab of the admin app. A super admin sees every admin's lines, an admin only their own.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final List<String> CATEGORIES =
            List.of("USERS", "KYC", "WAEC", "ADMINS", "WALLET", "MESSAGING", "PAYOUTS", "SECURITY", "SYSTEM");

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;

    public AuditService(TransactionTemplate tx) {
        this.tx = tx;
    }

    public record Entry(String id, Instant at, String category, String action, String summary, String actorName,
                        String actorEmail, String targetName, String detail, String ip) { }

    // ------------------------------------------------------------------ write

    /** Records one admin action. actor may be null only for system lines. */
    public void record(User actor, String category, String action, String summary, UUID targetId,
                       String targetName, String detail, String ip) {
        record(actor == null ? null : actor.getId(), actor == null ? null : actor.getFullName(),
                actor == null ? null : actor.getEmail(), category, action, summary, targetId, targetName, detail, ip, null);
    }

    public void record(UUID actorId, String actorName, String actorEmail, String category, String action,
                       String summary, UUID targetId, String targetName, String detail, String ip, Integer status) {
        try {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", UUID.randomUUID());
            c.put("category", cut(category, 20));
            c.put("action", cut(action, 40));
            c.put("summary", cut(summary, 200));
            c.put("actor_id", actorId);
            c.put("actor_name", cut(actorName, 200));
            c.put("actor_email", cut(actorEmail, 254));
            c.put("target_id", targetId);
            c.put("target_name", cut(targetName, 200));
            c.put("detail", cut(detail, 500));
            c.put("ip", cut(ip, 64));
            c.put("status_code", status);
            tx.executeWithoutResult(st -> insertRow("admin_audit", c));
        } catch (RuntimeException e) {
            log.warn("Audit: could not record '{}': {}", action, e.toString());
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** INSERT of only the non-null columns (an untyped null would be sent to PostgreSQL as bytea). */
    private void insertRow(String table, Map<String, Object> columns) {
        StringBuilder names = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> col : columns.entrySet()) {
            if (col.getValue() == null) continue;
            if (names.length() > 0) { names.append(", "); marks.append(", "); }
            names.append(col.getKey());
            marks.append(":p").append(values.size());
            values.add(col.getValue());
        }
        var q = em.createNativeQuery("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")");
        for (int i = 0; i < values.size(); i++) q.setParameter("p" + i, values.get(i));
        q.executeUpdate();
    }

    // ------------------------------------------------------------------ read

    @SuppressWarnings("unchecked")
    public List<Entry> list(User me, boolean superAdmin, String category, String search, Instant before, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        String cat = category == null ? "" : category.trim().toUpperCase();
        if (!cat.isEmpty() && !CATEGORIES.contains(cat)) throw new IllegalArgumentException("Unknown category.");
        String needle = search == null ? "" : search.trim().toLowerCase();

        StringBuilder sql = new StringBuilder(
                "SELECT x.id, x.created_at, x.category, x.action, x.summary, x.actor_name, x.actor_email, x.target_name, x.detail, x.ip FROM ("
                        + "SELECT CAST(a.id AS text) AS id, a.created_at, a.category, a.action, a.summary, a.actor_id, a.actor_name, a.actor_email, "
                        + "a.target_name, a.detail, a.ip FROM admin_audit a "
                        + "UNION ALL "
                        + "SELECT CAST(k.id AS text), k.created_at, CAST('KYC' AS varchar), k.action, "
                        + "CAST(CASE k.action WHEN 'APPROVED' THEN 'Approved a KYC request' WHEN 'REJECTED' THEN 'Rejected a KYC request' "
                        + "WHEN 'REVOKED' THEN 'Removed a user''s KYC verification' WHEN 'DETAILS_VIEWED' THEN 'Opened KYC details' "
                        + "WHEN 'VIEWED_PHOTO' THEN 'Viewed a KYC photo' ELSE 'KYC: ' || lower(replace(k.action, '_', ' ')) END AS varchar), "
                        + "k.actor_id, au.full_name, k.actor_email, uu.full_name, k.detail, CAST(NULL AS varchar) "
                        + "FROM kyc_audit k LEFT JOIN users uu ON uu.id = k.user_id LEFT JOIN users au ON au.id = k.actor_id "
                        + "WHERE k.actor_id IS NOT NULL AND k.action <> 'SUBMITTED'"
                        + ") x WHERE 1 = 1 ");
        Map<String, Object> params = new LinkedHashMap<>();
        if (!superAdmin) {
            sql.append("AND x.actor_id = :me ");
            params.put("me", me.getId());
        }
        if (!cat.isEmpty()) {
            sql.append("AND x.category = :cat ");
            params.put("cat", cat);
        }
        if (before != null) {
            sql.append("AND x.created_at < :before ");
            params.put("before", before);
        }
        if (!needle.isEmpty()) {
            sql.append("AND (LOWER(COALESCE(x.actor_name, '')) LIKE :q OR LOWER(COALESCE(x.actor_email, '')) LIKE :q "
                    + "OR LOWER(x.summary) LIKE :q OR LOWER(COALESCE(x.target_name, '')) LIKE :q OR LOWER(COALESCE(x.detail, '')) LIKE :q) ");
            params.put("q", "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append("ORDER BY x.created_at DESC LIMIT :lim");
        params.put("lim", size);

        var q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        List<Object[]> rows = q.getResultList();
        List<Entry> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new Entry((String) r[0], instant(r[1]), (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    (String) r[6], (String) r[7], (String) r[8], (String) r[9]));
        }
        return out;
    }

    // ------------------------------------------------------------------ maintenance (AuditMirrorJob)

    /** Copies WAEC and payout-recovery lines into admin_audit so they outlive the data-retention job. */
    void mirror() {
        tx.executeWithoutResult(st -> {
            em.createNativeQuery(
                    "INSERT INTO admin_audit (id, created_at, category, action, summary, actor_id, actor_name, actor_email, detail) "
                            + "SELECT w.id, w.created_at, 'WAEC', w.action, "
                            + "CASE w.action WHEN 'PINS_ADDED' THEN 'Added WAEC result pins' WHEN 'REVEAL' THEN 'Revealed WAEC pins' "
                            + "WHEN 'RESEND' THEN 'Resent WAEC pins' WHEN 'RESEND_NEW_EMAIL' THEN 'Resent WAEC pins to a new email' "
                            + "ELSE 'WAEC: ' || lower(replace(w.action, '_', ' ')) END, "
                            + "w.actor_id, u.full_name, w.actor_email, w.detail "
                            + "FROM waec_audit w LEFT JOIN users u ON u.id = w.actor_id "
                            + "WHERE w.actor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM admin_audit a WHERE a.id = w.id)").executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO admin_audit (id, created_at, category, action, summary, actor_id, actor_name, actor_email, target_name, detail) "
                            + "SELECT p.id, p.created_at, 'PAYOUTS', p.action, "
                            + "'Failed payout: ' || lower(replace(p.action, '_', ' ')), "
                            + "p.actor_id, u.full_name, p.actor_email, t.transaction_code, LEFT(p.detail, 500) "
                            + "FROM payout_recovery_actions p LEFT JOIN users u ON u.id = p.actor_id "
                            + "LEFT JOIN transactions t ON t.id = p.transaction_id "
                            + "WHERE p.actor_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM admin_audit a WHERE a.id = p.id)").executeUpdate();
        });
    }

    /** Deletes audit lines older than the retention window (app.audit.retention-days, default 365). */
    void purge(int days) {
        if (days <= 0) return;
        tx.executeWithoutResult(st -> em.createNativeQuery(
                "DELETE FROM admin_audit WHERE created_at < now() - (:d * interval '1 day')")
                .setParameter("d", days).executeUpdate());
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.util.Date dt) return dt.toInstant();
        return null;
    }
}
