package com.glr.deenwallet.kyc;

import com.glr.deenwallet.transaction.TransactionRepository;
import com.glr.deenwallet.transaction.TransactionServiceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

/**
 * Daily conversion limits that depend on the customer's identity verification.
 *
 *  - Email-only (not ID-verified):  mobile money SLE 1,000 / day, bank transfer SLE 500 / day.
 *  - ID-verified (KYC approved):    SLE 100,000 / day, for each of mobile money and bank transfer.
 *  - WAEC result pins:              never limited here (verified or not).
 *
 * "Day" is the calendar day in Freetown (UTC+0), so it resets at midnight and is easy to explain.
 * Cancelled, failed and refunded transfers do not use up the limit. All amounts are minor units (SLE x 100).
 * The limits can be changed with the env variables below without touching the code.
 */
@Service
public class KycLimitService {

    public static final ZoneId ZONE = ZoneId.of("Africa/Freetown");

    @Value("${KYC_BASIC_MOBILE_MONEY_DAILY_MINOR:100000}")
    private long basicMobileMoneyMinor;

    @Value("${KYC_BASIC_BANK_DAILY_MINOR:50000}")
    private long basicBankMinor;

    @Value("${KYC_VERIFIED_DAILY_MINOR:10000000}")
    private long verifiedMinor;

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

    /** Limits and today's usage for the KYC screen. */
    public Snapshot snapshot(UUID userId) {
        boolean verified = isVerified(userId);
        Instant since = startOfToday();
        return new Snapshot(verified,
                bucket(limitFor(TransactionServiceType.MOBILE_MONEY, verified),
                        transactions.sumKycCountedAmountSince(userId, TransactionServiceType.MOBILE_MONEY, since)),
                bucket(limitFor(TransactionServiceType.BANK_TRANSFER, verified),
                        transactions.sumKycCountedAmountSince(userId, TransactionServiceType.BANK_TRANSFER, since)));
    }

    /**
     * Throws KycLimitExceededException (HTTP 400 with a clear message) if this conversion would take the
     * customer over today's limit. Call it inside the same per-user lock that creates the transaction.
     */
    public void enforce(UUID userId, TransactionServiceType type, long minorUnits) {
        if (type != TransactionServiceType.MOBILE_MONEY && type != TransactionServiceType.BANK_TRANSFER) {
            return; // WAEC pins: allowed whether verified or not
        }
        boolean verified = isVerified(userId);
        long limit = limitFor(type, verified);
        long used = transactions.sumKycCountedAmountSince(userId, type, startOfToday());
        if (used + minorUnits <= limit) return;

        long remaining = Math.max(0L, limit - used);
        String what = type == TransactionServiceType.BANK_TRANSFER ? "bank transfers" : "mobile money";
        StringBuilder msg = new StringBuilder();
        if (remaining == 0L) {
            msg.append("Daily limit reached. ");
        } else {
            msg.append("This amount is above what is left of your daily limit. ");
        }
        if (verified) {
            msg.append("Your limit for ").append(what).append(" is SLE ").append(sle(limit))
                    .append(" per day and you have SLE ").append(sle(remaining))
                    .append(" left today. It resets at midnight.");
        } else {
            msg.append("Accounts that are not ID-verified can use up to SLE ").append(sle(limit))
                    .append(" per day for ").append(what).append(" (SLE ").append(sle(remaining))
                    .append(" left today). ");
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

    private long limitFor(TransactionServiceType type, boolean verified) {
        if (verified) return verifiedMinor;
        return type == TransactionServiceType.BANK_TRANSFER ? basicBankMinor : basicMobileMoneyMinor;
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
