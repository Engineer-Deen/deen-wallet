package com.glr.deenwallet.admin;

import com.glr.deenwallet.kyc.KycStatus;
import com.glr.deenwallet.kyc.KycSubmissionRepository;
import com.glr.deenwallet.user.UserRepository;
import com.glr.deenwallet.waec.WaecPinService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Numbers for the admin Home page. Access: /api/admin/** is already restricted to ADMIN / SUPER_ADMIN.
 *
 * Today = completed transactions since 00:00 UTC. "This month" fees/volume = daily_stats for older days
 * (kept after old transactions are wiped) plus live figures for yesterday and today, which the nightly
 * snapshot may not have finished yet.
 */
@RestController
@RequestMapping("/api/admin/home")
public class AdminHomeController {

    private static final long CACHE_MS = 20_000;

    public record Home(long totalUsers, long emailVerifiedUsers, long kycVerifiedUsers,
                       long failedPayouts, long kycPending,
                       long waecAvailable, boolean waecOperational,
                       long todayTransactions, double todayVolumeSle,
                       long monthTransactions, double monthVolumeSle, double monthFeesSle) { }

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final KycSubmissionRepository kycSubmissions;
    private final WaecPinService waecPins;

    private volatile Home cached;
    private volatile long cachedAt;

    public AdminHomeController(UserRepository users, KycSubmissionRepository kycSubmissions, WaecPinService waecPins) {
        this.users = users;
        this.kycSubmissions = kycSubmissions;
        this.waecPins = waecPins;
    }

    @GetMapping
    public ResponseEntity<Home> home() {
        long now = System.currentTimeMillis();
        Home c = cached;
        if (c != null && now - cachedAt < CACHE_MS) return ResponseEntity.ok(c);
        Home fresh = compute();
        cached = fresh;
        cachedAt = now;
        return ResponseEntity.ok(fresh);
    }

    private Home compute() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate liveFromDay = today.minusDays(1);               // yesterday and today are read live
        Instant todayStart = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant liveFrom = (liveFromDay.isBefore(monthStart) ? monthStart : liveFromDay)
                .atStartOfDay().toInstant(ZoneOffset.UTC);

        long failedPayouts = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE status IN ('PAYOUT_FAILED','REFUND_PENDING','NEEDS_REVIEW')")
                .getSingleResult()).longValue();

        Object[] t = (Object[]) em.createNativeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(amount_value), 0) FROM transactions "
                                + "WHERE status = 'COMPLETED' AND created_at >= :from")
                .setParameter("from", todayStart).getSingleResult();

        Object[] live = (Object[]) em.createNativeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(amount_value), 0), COALESCE(SUM(deen_wallet_fee_value), 0) "
                                + "FROM transactions WHERE status = 'COMPLETED' AND created_at >= :from")
                .setParameter("from", liveFrom).getSingleResult();

        Object[] old = (Object[]) em.createNativeQuery(
                        "SELECT COALESCE(SUM(tx_count), 0), COALESCE(SUM(volume_value), 0), COALESCE(SUM(fees_value), 0) "
                                + "FROM daily_stats WHERE day >= :monthStart AND day < :liveDay")
                .setParameter("monthStart", monthStart)
                .setParameter("liveDay", liveFromDay.isBefore(monthStart) ? monthStart : liveFromDay)
                .getSingleResult();

        long monthTx = n(live[0]) + n(old[0]);
        long monthVol = n(live[1]) + n(old[1]);
        long monthFees = n(live[2]) + n(old[2]);

        return new Home(
                users.count(),
                users.countByEmailVerifiedTrue(),
                kycSubmissions.countByStatus(KycStatus.APPROVED),
                failedPayouts,
                kycSubmissions.countByStatus(KycStatus.PENDING),
                waecPins.summary().available(),
                waecPins.isOperational(),
                n(t[0]), n(t[1]) / 100.0,
                monthTx, monthVol / 100.0, monthFees / 100.0);
    }

    private static long n(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
