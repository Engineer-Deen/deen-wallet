package com.glr.deenwallet.kyc;

import com.glr.deenwallet.transaction.TransactionRepository;
import com.glr.deenwallet.transaction.TransactionServiceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

/**
 * Daily conversion limits that depend on the customer's identity verification.
 *
 *  One general daily transaction limit that all services share (mobile money, bank transfer, send abroad, crypto):
 *  - Not ID-verified:   SLE 1,000 / day in total.
 *  - ID-verified (KYC): SLE 200,000 / day in total.
 *  - WAEC result pins:  never limited here (verified or not).
 *
 * "Day" is the calendar day in Freetown (UTC+0), so it resets at midnight and is easy to explain.
 * Cancelled, failed and refunded transfers do not use up the limit. All amounts are minor units (SLE x 100).
 * The limits can be changed with the env variables below without touching the code.
 */
@Service
public class KycLimitService {

    public static final ZoneId ZONE = ZoneId.of("Africa/Freetown");

    @Value("${KYC_DAILY_LIMIT_BASIC_MINOR:100000}")
    private long basicMinor;

    @Value("${KYC_DAILY_LIMIT_VERIFIED_MINOR:20000000}")
    private long verifiedMinor;

    @PersistenceContext
    private EntityManager em;

    private final TransactionRepository transactions;
    private final KycSubmissionRepository submissions;

    public KycLimitService(TransactionRepository transactions, KycSubmissionRepository submissions) {
        this.transactions = transactions;
        this.submissions = submissions;
    }

    public record Snapshot(boolean verified, KycDtos.LimitView mobileMoney, KycDtos.LimitView bank) { }

    public boolean isVerified(UUID userId) {
        return submissions.existsByUserIdAndStatus(userId, KycStatus.APPROVED);
    }

    public long verifiedDailyLimitMinor() {
        return verifiedMinor;
    }

    /** The general limit and today's usage for the KYC screen (mobileMoney and bank both carry the same general numbers). */
    public Snapshot snapshot(UUID userId) {
        boolean verified = isVerified(userId);
        KycDtos.LimitView general = bucket(limitFor(verified), usedToday(userId));
        return new Snapshot(verified, general, general);
    }

    /**
     * Throws KycLimitExceededException (HTTP 400 with a clear message) if this conversion would take the
     * customer over today's general limit. Call it inside the same per-user lock that creates the transaction.
     */
    public void enforce(UUID userId, TransactionServiceType type, long minorUnits) {
        if (type != TransactionServiceType.MOBILE_MONEY && type != TransactionServiceType.BANK_TRANSFER) {
            return; // WAEC pins: allowed whether verified or not
        }
        enforceGeneral(userId, minorUnits);
    }

    /** The same general daily limit for any other service (send abroad, crypto). */
    public void enforceGeneral(UUID userId, long minorUnits) {
        boolean verified = isVerified(userId);
        long limit = limitFor(verified);
        long used = usedToday(userId);
        if (used + minorUnits <= limit) return;

        long remaining = Math.max(0L, limit - used);
        StringBuilder msg = new StringBuilder();
        if (remaining == 0L) {
            msg.append("Daily transaction limit reached. ");
        } else {
            msg.append("This amount is above what is left of your daily transaction limit. ");
        }
        if (verified) {
            msg.append("Your daily transaction limit is SLE ").append(sle(limit))
                    .append(" and you have SLE ").append(sle(remaining))
                    .append(" left today. It resets at midnight.");
        } else {
            msg.append("Accounts that are not ID-verified have a daily transaction limit of SLE ").append(sle(limit))
                    .append(" (SLE ").append(sle(remaining)).append(" left today). ");
            boolean pending = submissions.findOpenByUserId(userId)
                    .map(s -> s.getStatus() == KycStatus.PENDING).orElse(false);
            if (pending) {
                msg.append("Your ID verification is being reviewed; once it is approved your limit rises to SLE ")
                        .append(sle(verifiedMinor)).append(" per day.");
            } else {
                msg.append("Verify your identity under \"Verify your KYC\" to raise it to SLE ")
                        .append(sle(verifiedMinor)).append(" per day.");
            }
        }
        throw new KycLimitExceededException(msg.toString());
    }

    private long limitFor(boolean verified) {
        return verified ? verifiedMinor : basicMinor;
    }

    /** Everything counted today across all services except WAEC: mobile money, bank, send abroad and crypto. */
    private long usedToday(UUID userId) {
        Instant since = startOfToday();
        long used = transactions.sumKycCountedAmountSince(userId, TransactionServiceType.MOBILE_MONEY, since)
                + transactions.sumKycCountedAmountSince(userId, TransactionServiceType.BANK_TRANSFER, since);
        java.sql.Timestamp ts = java.sql.Timestamp.from(since);
        used += scalar("SELECT CAST(COALESCE(SUM(ROUND(send_amount * 100)), 0) AS BIGINT) FROM cross_border_transfers "
                + "WHERE user_id = :u AND created_at >= :s AND status NOT IN ('FAILED','REFUNDED','REFUNDING')", userId, ts);
        used += scalar("SELECT CAST(COALESCE(SUM(amount_minor), 0) AS BIGINT) FROM crypto_orders "
                + "WHERE user_id = :u AND created_at >= :s AND status IN ('CREATED','PENDING','SUCCESSFUL')", userId, ts);
        return used;
    }

    private long scalar(String sql, UUID userId, java.sql.Timestamp since) {
        Object r = em.createNativeQuery(sql).setParameter("u", userId).setParameter("s", since).getSingleResult();
        return r == null ? 0L : ((Number) r).longValue();
    }

    private static KycDtos.LimitView bucket(long limit, long used) {
        return new KycDtos.LimitView(limit, used, Math.max(0L, limit - used));
    }

    static Instant startOfToday() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }

    /** 100000 -> "1,000", 150050 -> "1,500.50". */
    static String sle(long minor) {
        BigDecimal value = BigDecimal.valueOf(minor, 2);
        if (minor % 100 == 0) {
            return String.format(Locale.US, "%,d", minor / 100);
        }
        return String.format(Locale.US, "%,.2f", value);
    }
}
