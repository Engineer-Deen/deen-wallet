package com.glr.deenwallet.safety;

import com.glr.deenwallet.audit.AuditService;
import com.glr.deenwallet.notification.NotificationResponse;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Safety rails around real money:
 *  - the emergency freeze (stops new transfers, payouts, refunds and withdrawals at once; money already collected is
 *    kept and is paid out after a super admin resumes),
 *  - withdrawal limits (set on the server with APP_WALLET_MAX_SINGLE_MINOR and APP_WALLET_MAX_DAILY_MINOR),
 *  - alerts to every super admin when something money-related looks wrong,
 *  - a watchdog that raises an alert when money is stuck.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneySafetyService {

    private static final short ROW = 1;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "money-safety-alerts");
        t.setDaemon(true);
        return t;
    });

    private final MoneySafetyRepository repo;
    private final NotificationService notifications;
    private final PushNotificationService push;
    private final AuditService audit;

    @PersistenceContext
    private EntityManager em;

    /** Largest single withdrawal, in minor units (default SLE 10,000,000.00). */
    @Value("${app.wallet.max-single-minor:1000000000}")
    private long maxSingleMinor;

    /** Most one admin can withdraw in any 24 hours, in minor units (default SLE 20,000,000.00). */
    @Value("${app.wallet.max-daily-minor:2000000000}")
    private long maxDailyMinor;

    /** Read every payment back from the payment provider before paying out (turn off only if told to). */
    @Value("${app.safety.verify-cash-in:true}")
    private boolean verifyCashIn;

    private final Map<String, Instant> alertSeen = new ConcurrentHashMap<>();

    public record Status(boolean frozen, String reason, Instant changedAt, long maxSingleMinor, long maxDailyMinor) { }

    // ------------------------------------------------------------------ freeze

    public boolean isFrozen() {
        return repo.findById(ROW).map(MoneySafety::isFrozen).orElse(false);
    }

    /** For customers starting a transfer. */
    public void requireOpenForCustomers() {
        if (isFrozen()) {
            throw new IllegalStateException("Transfers are paused for a short while. You have not been charged. Please try again soon.");
        }
    }

    /** For admin actions that move money. */
    public void requireOpenForAdmins() {
        if (isFrozen()) {
            throw new IllegalStateException("Money movement is paused. A super admin can resume it in Menu > Security.");
        }
    }

    public long maxSingleMinor() {
        return maxSingleMinor;
    }

    public long maxDailyMinor() {
        return maxDailyMinor;
    }

    public boolean verifyCashInEnabled() {
        return verifyCashIn;
    }

    public Status status() {
        MoneySafety s = repo.findById(ROW).orElse(null);
        return new Status(s != null && s.isFrozen(), s == null ? null : s.getReason(),
                s == null ? null : s.getChangedAt(), maxSingleMinor, maxDailyMinor);
    }

    @Transactional
    public Status setFrozen(User actor, boolean frozen, String reason) {
        MoneySafety s = repo.findById(ROW).orElseGet(() -> MoneySafety.builder().id(ROW).build());
        String clean = reason == null ? null : reason.trim();
        if (clean != null && clean.length() > 300) clean = clean.substring(0, 300);
        if (clean != null && clean.isEmpty()) clean = null;
        s.setFrozen(frozen);
        s.setReason(frozen ? clean : null);
        s.setChangedBy(actor.getId());
        s.setChangedAt(Instant.now());
        repo.save(s);
        audit.record(actor, "SECURITY", frozen ? "MONEY_FREEZE" : "MONEY_RESUME",
                frozen ? "Froze all money movement" : "Resumed money movement", null, null, clean, null);
        alert(frozen ? "MONEY_FROZEN" : "MONEY_RESUMED",
                frozen ? "Money movement frozen" : "Money movement resumed",
                actor.getFullName() + (frozen ? " froze all money movement." : " resumed money movement.")
                        + (frozen && clean != null ? " Reason: " + clean : ""));
        return status();
    }

    // ------------------------------------------------------------------ alerts

    /** Logs an ALERT line and notifies every active super admin. Never throws. */
    public void alert(String type, String title, String message) {
        log.error("ALERT {}: {} - {}", type, title, message);
        try {
            POOL.execute(() -> sendToSuperAdmins(type, title, message));
        } catch (RuntimeException e) {
            log.warn("Money alert could not be queued: {}", e.toString());
        }
    }

    /** Same, but the same key is only raised once an hour, so a retry loop cannot flood the admins. */
    public void alertOnce(String key, String type, String title, String message) {
        Instant now = Instant.now();
        Instant last = alertSeen.get(key);
        if (last != null && last.isAfter(now.minus(Duration.ofHours(1)))) return;
        if (alertSeen.size() > 5000) alertSeen.clear();
        alertSeen.put(key, now);
        alert(type, title, message);
    }

    @SuppressWarnings("unchecked")
    private void sendToSuperAdmins(String type, String title, String message) {
        try {
            List<Object> ids = em.createNativeQuery(
                    "SELECT id FROM users WHERE role = 'SUPER_ADMIN' AND active = TRUE").getResultList();
            for (Object o : ids) {
                UUID adminId = o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
                try {
                    NotificationResponse n = notifications.create(adminId, type, title, message, null, null);
                    push.sendToUser(adminId, title, message, Map.of("type", type, "notificationId", n.id().toString()));
                } catch (RuntimeException e) {
                    log.warn("Money alert for {} failed: {}", adminId, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Money alert '{}' failed: {}", type, e.toString());
        }
    }

    // ------------------------------------------------------------------ watchdog

    /** Every 5 minutes: raises an alert when money looks stuck. Read-only; it never changes anything. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void watchdog() {
        try {
            if (!isFrozen()) {
                long paidNotSent = count("SELECT COUNT(*) FROM transactions WHERE status = 'PAID_IN' AND monime_payout_id IS NULL "
                        + "AND COALESCE(paid_in_at, created_at) < now() - interval '15 minutes'");
                if (paidNotSent > 0) {
                    alertOnce("wd:paidin", "STUCK_PAID_IN", "Paid but not sent",
                            paidNotSent + " transfer(s) were paid by the customer more than 15 minutes ago and have not been sent out yet.");
                }
                long longPaying = count("SELECT COUNT(*) FROM transactions WHERE status = 'PAYING_OUT' "
                        + "AND COALESCE(paid_in_at, created_at) < now() - interval '3 hours'");
                if (longPaying > 0) {
                    alertOnce("wd:payingout", "STUCK_PAYING_OUT", "Payout taking too long",
                            longPaying + " payout(s) have been waiting for the payment provider for over 3 hours.");
                }
            }
            long oldFailed = count("SELECT COUNT(*) FROM transactions WHERE status = 'PAYOUT_FAILED' "
                    + "AND COALESCE(payout_failed_at, updated_at) < now() - interval '12 hours'");
            if (oldFailed > 0) {
                alertOnce("wd:failed", "OLD_FAILED_PAYOUT", "Failed payouts waiting",
                        oldFailed + " failed payout(s) have been waiting for an admin for over 12 hours. Open Failed Payouts.");
            }
            long oldRefund = count("SELECT COUNT(*) FROM transactions WHERE status IN ('REFUND_PENDING', 'NEEDS_REVIEW') "
                    + "AND updated_at < now() - interval '12 hours'");
            if (oldRefund > 0) {
                alertOnce("wd:refund", "OLD_REFUND", "Refunds waiting",
                        oldRefund + " refund(s) have not finished after 12 hours. Open Failed Payouts.");
            }
            long reviewWithdrawals = count("SELECT COUNT(*) FROM wallet_withdrawals WHERE status = 'REVIEW'");
            if (reviewWithdrawals > 0) {
                alertOnce("wd:withdraw", "WITHDRAWAL_REVIEW", "Withdrawal needs review",
                        reviewWithdrawals + " wallet withdrawal(s) were not confirmed by the payment provider. Check before trying again.");
            }
        } catch (Exception e) {
            log.warn("Money watchdog could not run: {}", e.toString());
        }
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
