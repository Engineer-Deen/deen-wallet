package com.glr.deenwallet.maintenance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Nightly wipe of customer activity records older than app.data-retention.days (default 30):
 *
 *   - transactions (finished ones only) and their recovery-action rows
 *   - transactions_archive
 *   - notifications
 *   - WAEC: sold pins, purchase orders and the WAEC audit trail
 *
 * NEVER touched here: kyc_submissions, kyc_documents, kyc_audit (KYC data is kept), users,
 * saved recipients, AVAILABLE / RESERVED WAEC pins (that is unsold stock), and any transaction
 * that still needs attention (AWAITING_PAYMENT, PAID_IN, PAYING_OUT, PAYOUT_FAILED,
 * REFUND_PENDING, NEEDS_REVIEW) - those hold customer money and stay until they are resolved.
 *
 * Settings (application.yml / environment):
 *   app.data-retention.days     DATA_RETENTION_DAYS     default 30
 *   app.data-retention.enabled  DATA_RETENTION_ENABLED  default true
 */
@Component
public class RecordRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RecordRetentionJob.class);

    /** Transaction states that are finished: nothing is waiting on them any more. */
    private static final String FINAL_STATUSES = "'COMPLETED','FAILED','CANCELED','REFUNDED'";
    private static final int BATCH = 1000;
    private static final int MAX_BATCHES = 500;   // up to 500k rows per table per night
    private static final int MIN_DAYS = 2;        // daily KYC limits look back 24h; never wipe fresher data

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;

    @Value("${app.data-retention.days:30}")
    private int days;

    @Value("${app.data-retention.enabled:true}")
    private boolean enabled;

    public RecordRetentionJob(TransactionTemplate tx) {
        this.tx = tx;
    }

    @Scheduled(cron = "0 45 3 * * *")
    public void purge() {
        if (!enabled) {
            log.info("Record retention is disabled (app.data-retention.enabled=false)");
            return;
        }
        if (days < MIN_DAYS) {
            log.error("Record retention skipped: app.data-retention.days={} is below the safe minimum of {}", days, MIN_DAYS);
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        log.info("Record retention: wiping activity records older than {} days (before {})", days, cutoff);

        step("notifications", () -> simple(
                "DELETE FROM notifications WHERE id IN (SELECT id FROM notifications WHERE created_at < :cutoff LIMIT :n)",
                cutoff));

        step("waec_audit", () -> simple(
                "DELETE FROM waec_audit WHERE id IN (SELECT id FROM waec_audit WHERE created_at < :cutoff LIMIT :n)",
                cutoff));

        // Only pins that were SOLD. AVAILABLE and RESERVED pins are stock and are never removed.
        step("waec_pins (sold)", () -> simple(
                "DELETE FROM waec_pins WHERE id IN (SELECT id FROM waec_pins WHERE status = 'SOLD' "
                        + "AND COALESCE(sold_at, created_at) < :cutoff LIMIT :n)",
                cutoff));

        // Orders whose purchase is still in flight are kept until the transaction is finished.
        step("waec_orders", () -> simple(
                "DELETE FROM waec_orders WHERE id IN (SELECT o.id FROM waec_orders o WHERE o.created_at < :cutoff "
                        + "AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.id = o.transaction_id "
                        + "AND t.status NOT IN (" + FINAL_STATUSES + ")) LIMIT :n)",
                cutoff));

        step("transactions", () -> transactions(cutoff));

        step("transactions_archive", () -> simple(
                "DELETE FROM transactions_archive WHERE id IN (SELECT id FROM transactions_archive WHERE created_at < :cutoff LIMIT :n)",
                cutoff));
    }

    /** Repeats a small batched DELETE until nothing is left (or the nightly cap is reached). */
    private int simple(String sql, Instant cutoff) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            Integer deleted = tx.execute(status ->
                    em.createNativeQuery(sql)
                            .setParameter("cutoff", cutoff)
                            .setParameter("n", BATCH)
                            .executeUpdate());
            int d = deleted == null ? 0 : deleted;
            total += d;
            if (d < BATCH) break;
        }
        return total;
    }

    /** payout_recovery_actions has a foreign key to transactions, so its rows go first, in the same batch. */
    private int transactions(Instant cutoff) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            Integer deleted = tx.execute(status -> {
                @SuppressWarnings("unchecked")
                List<UUID> ids = em.createNativeQuery(
                                "SELECT id FROM transactions WHERE status IN (" + FINAL_STATUSES + ") "
                                        + "AND created_at < :cutoff ORDER BY created_at LIMIT :n", UUID.class)
                        .setParameter("cutoff", cutoff)
                        .setParameter("n", BATCH)
                        .getResultList();
                if (ids.isEmpty()) return 0;
                em.createNativeQuery("DELETE FROM payout_recovery_actions WHERE transaction_id IN (:ids)")
                        .setParameter("ids", ids)
                        .executeUpdate();
                return em.createNativeQuery("DELETE FROM transactions WHERE id IN (:ids)")
                        .setParameter("ids", ids)
                        .executeUpdate();
            });
            int d = deleted == null ? 0 : deleted;
            total += d;
            if (d < BATCH) break;
        }
        return total;
    }

    /** Each table is independent: one failure is logged and never blocks the others. */
    private void step(String name, java.util.function.IntSupplier work) {
        try {
            log.info("Record retention: removed {} rows from {}", work.getAsInt(), name);
        } catch (Exception e) {
            log.error("Record retention: failed on {}", name, e);
        }
    }
}
