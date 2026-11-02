package com.glr.deenwallet.paylink;

import com.glr.deenwallet.monime.MonimeClient;
import com.glr.deenwallet.monime.ProviderKycResult;
import com.glr.deenwallet.safety.MoneySafetyService;
import com.glr.deenwallet.transaction.BankAccountVerificationResponse;
import com.glr.deenwallet.transaction.BankTransferService;
import com.glr.deenwallet.transaction.ConversionFee;
import com.glr.deenwallet.transaction.PaymentLinkPayment;
import com.glr.deenwallet.transaction.TooManyRequestsException;
import com.glr.deenwallet.transaction.Transaction;
import com.glr.deenwallet.transaction.TransactionService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Payment links. A customer creates a link (fixed amount or "any amount", one-time or reusable) and shares it.
 * Anyone with the link can pay it with mobile money without an account: they enter their number, dial a one-time
 * USSD code, and the money is sent to the account the link owner chose. Every payment is a normal transaction
 * (see TransactionService.initiateForPaymentLink), so the existing safety checks, payout handling, refunds and
 * admin tools all apply to it.
 *
 * Settings in the env file (optional): APP_PAYLINK_GUEST_MAX_MINOR (largest single payment, default SLE 2,000.00),
 * APP_PAYLINK_GUEST_DAILY_MINOR (most one payer phone can pay in 24 hours, default SLE 5,000.00),
 * APP_PUBLIC_BASE_URL (default https://api.deenwallapp.com).
 */
@Service
public class PaymentRequestService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestService.class);
    private static final Pattern PHONE = Pattern.compile("^\\+232\\d{8}$");
    private static final Pattern MOMO_PROVIDER = Pattern.compile("^m1[78]$");
    private static final String SLUG_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int MAX_ACTIVE_LINKS = 20;
    private static final long TWENTY_MIN_MS = 20L * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The status shown to people: closed / expired / paid (one-time link that was paid) / active. */
    private static final String EFFECTIVE_STATUS =
            "CASE WHEN r.status = 'CLOSED' THEN 'CLOSED' "
                    + "WHEN r.expires_at IS NOT NULL AND r.expires_at < now() THEN 'EXPIRED' "
                    + "WHEN NOT r.reusable AND EXISTS (SELECT 1 FROM transactions x WHERE x.payment_request_id = r.id AND x.status = 'COMPLETED') THEN 'PAID' "
                    + "ELSE 'ACTIVE' END";

    private static final String COLUMNS =
            "r.id, r.owner_user_id, r.slug, r.title, r.note, r.amount_value, r.reusable, r.dest_type, r.dest_provider_id, "
                    + "r.dest_account, r.dest_holder_name, r.dest_bank_name, " + EFFECTIVE_STATUS + ", "
                    + "CAST(EXTRACT(EPOCH FROM r.expires_at) * 1000 AS bigint), CAST(EXTRACT(EPOCH FROM r.created_at) * 1000 AS bigint), "
                    + "(SELECT COUNT(*) FROM transactions t WHERE t.payment_request_id = r.id AND t.status = 'COMPLETED'), "
                    + "(SELECT COALESCE(SUM(t.amount_value), 0) FROM transactions t WHERE t.payment_request_id = r.id AND t.status = 'COMPLETED'), "
                    + "(SELECT COUNT(*) FROM transactions t WHERE t.payment_request_id = r.id AND t.status IN ('PAID_IN', 'PAYING_OUT', 'PAYOUT_FAILED'))";

    private final TransactionService transactions;
    private final UserRepository users;
    private final BankTransferService banks;
    private final MonimeClient monime;
    private final MoneySafetyService safety;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    @Value("${app.paylink.guest-max-minor:200000}")
    private long guestMaxMinor;

    @Value("${app.paylink.guest-daily-minor:500000}")
    private long guestDailyMinor;

    @Value("${app.public-base-url:https://api.deenwallapp.com}")
    private String baseUrl;

    private final Map<String, Deque<Long>> starts = new ConcurrentHashMap<>();

    public PaymentRequestService(TransactionService transactions, UserRepository users, BankTransferService banks,
                                 MonimeClient monime, MoneySafetyService safety, TransactionTemplate tx) {
        this.transactions = transactions;
        this.users = users;
        this.banks = banks;
        this.monime = monime;
        this.safety = safety;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ owner: create / list / detail / close

    public Map<String, Object> create(UUID ownerId, CreateRequest r) {
        safety.requireOpenForCustomers();
        User owner = users.findById(ownerId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!owner.isActive() || owner.isLocked()) throw new IllegalStateException("Your account is not available right now. Please contact support.");
        if (!owner.isEmailVerified()) throw new IllegalStateException("Please verify your email first.");

        String title = clean(r.getTitle(), 80);
        if (title.isEmpty()) throw new IllegalArgumentException("Give your payment link a title, for example \"Rent\" or \"Order 12\".");
        String note = clean(r.getNote(), 300);

        Long amountMinor = null;
        if (r.getAmount() != null) {
            BigDecimal a = r.getAmount();
            if (a.compareTo(BigDecimal.ONE) < 0 || a.scale() > 2) {
                throw new IllegalArgumentException("The amount must be at least 1.00 and have at most 2 decimal places.");
            }
            amountMinor = a.movePointRight(2).longValueExact();
            if (amountMinor > guestMaxMinor) {
                throw new IllegalArgumentException("The amount is above the most one payment link can take (SLE " + money(guestMaxMinor) + ").");
            }
        }
        int days = r.getExpiresInDays() == null ? 0 : r.getExpiresInDays();
        if (days != 0 && days != 1 && days != 7 && days != 30) throw new IllegalArgumentException("Choose how long the link should stay open.");
        boolean reusable = Boolean.TRUE.equals(r.getReusable());

        long active = count("SELECT COUNT(*) FROM payment_requests r WHERE r.owner_user_id = :o AND " + EFFECTIVE_STATUS + " = 'ACTIVE'", "o", ownerId);
        if (active >= MAX_ACTIVE_LINKS) throw new IllegalStateException("You have " + MAX_ACTIVE_LINKS + " active payment links. Close one you no longer need first.");

        boolean bank = "BANK".equalsIgnoreCase(r.getDestType());
        String destProvider;
        String destAccount;
        String holder;
        String bankName = null;
        if (bank) {
            BankAccountVerificationResponse v = banks.consumeVerification(ownerId, r.getVerificationToken(), r.getBankProviderId(), r.getAccountNumber());
            destProvider = v.providerId();
            destAccount = v.accountNumber();
            holder = v.holderName();
            bankName = v.bankName();
        } else {
            destProvider = r.getProviderId() == null ? "" : r.getProviderId().trim();
            destAccount = r.getPhone() == null ? "" : r.getPhone().trim();
            if (!MOMO_PROVIDER.matcher(destProvider).matches()) throw new IllegalArgumentException("Choose Orange Money or Africell for the account that will receive the money.");
            if (!PHONE.matcher(destAccount).matches()) throw new IllegalArgumentException("Enter the number as +232XXXXXXXX.");
            ProviderKycResult kyc;
            try {
                kyc = monime.getProviderKyc(destProvider, destAccount);
            } catch (org.springframework.web.client.RestClientResponseException | org.springframework.web.client.ResourceAccessException e) {
                throw new IllegalStateException("We could not check that mobile money account right now. Please try again in a moment.");
            }
            holder = kyc == null || kyc.account() == null ? null : kyc.account().holderName();
            if (holder == null || holder.isBlank()) throw new IllegalArgumentException("We could not find an account name for that number. Check it and try again.");
            holder = holder.trim().replaceAll("\\s+", " ");
        }

        final UUID id = UUID.randomUUID();
        final String slug = newSlug();
        final Long fAmount = amountMinor;
        final String fNote = note.isEmpty() ? null : note;
        final String fTitle = title;
        final String fDestProvider = destProvider;
        final String fDestAccount = destAccount;
        final String fHolder = holder;
        final String fBankName = bankName;
        final boolean fReusable = reusable;
        final int fDays = days;
        final String amountSql = fAmount == null ? "NULL" : ":a";
        final String noteSql = fNote == null ? "NULL" : ":n";
        final String bankSql = fBankName == null ? "NULL" : ":db";
        final String expiresSql = fDays > 0 ? "now() + interval '" + fDays + " days'" : "NULL";   // fDays is 1, 7 or 30 here
        tx.executeWithoutResult(st -> {
            jakarta.persistence.Query q = em.createNativeQuery(
                    "INSERT INTO payment_requests (id, owner_user_id, slug, title, note, amount_value, reusable, dest_type, dest_provider_id, "
                            + "dest_account, dest_holder_name, dest_bank_name, status, expires_at) "
                            + "VALUES (:id, :o, :s, :t, " + noteSql + ", " + amountSql + ", :ru, :dt, :dp, :da, :dh, " + bankSql + ", 'ACTIVE', " + expiresSql + ")");
            q.setParameter("id", id).setParameter("o", ownerId).setParameter("s", slug).setParameter("t", fTitle)
                    .setParameter("ru", fReusable).setParameter("dt", bank ? "BANK" : "MOMO").setParameter("dp", fDestProvider)
                    .setParameter("da", fDestAccount).setParameter("dh", fHolder);
            if (fAmount != null) q.setParameter("a", fAmount);
            if (fNote != null) q.setParameter("n", fNote);
            if (fBankName != null) q.setParameter("db", fBankName);
            q.executeUpdate();
        });
        log.info("Payment link {} created by {}", slug, ownerId);
        return ownerView(loadOne("r.id = :v", id, ownerId));
    }

    public List<Map<String, Object>> list(UUID ownerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS + " FROM payment_requests r WHERE r.owner_user_id = :o "
                + "ORDER BY r.created_at DESC LIMIT 50").setParameter("o", ownerId).getResultList();
        for (Object[] row : rows) out.add(ownerView(row));
        return out;
    }

    public Map<String, Object> detail(UUID ownerId, UUID id) {
        Object[] row = loadOne("r.id = :v", id, ownerId);
        Map<String, Object> view = ownerView(row);
        List<Map<String, Object>> payments = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT t.id, t.transaction_code, t.amount_value, t.status, t.payer_name, t.source_phone, CAST(EXTRACT(EPOCH FROM t.created_at) * 1000 AS bigint) "
                        + "FROM transactions t WHERE t.payment_request_id = :id "
                        + "AND NOT (t.status IN ('CANCELED', 'FAILED') AND t.paid_in_at IS NULL) "
                        + "AND NOT (t.status = 'AWAITING_PAYMENT' AND t.created_at < now() - interval '15 minutes') "
                        + "ORDER BY t.created_at DESC LIMIT 50").setParameter("id", id).getResultList();
        for (Object[] x : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(x[0]));
            m.put("reference", x[1]);
            m.put("amountMinor", ((Number) x[2]).longValue());
            m.put("state", ownerState(String.valueOf(x[3])));
            m.put("payerName", x[4]);
            m.put("payerPhone", maskPhone(String.valueOf(x[5])));
            m.put("createdAt", ((Number) x[6]).longValue());
            payments.add(m);
        }
        view.put("payments", payments);
        return view;
    }

    public Map<String, Object> close(UUID ownerId, UUID id) {
        loadOne("r.id = :v", id, ownerId);   // must exist and belong to the owner
        tx.executeWithoutResult(st -> em.createNativeQuery(
                "UPDATE payment_requests SET status = 'CLOSED', closed_at = now(), updated_at = now() WHERE id = :id AND owner_user_id = :o AND status = 'ACTIVE'")
                .setParameter("id", id).setParameter("o", ownerId).executeUpdate());
        return ownerView(loadOne("r.id = :v", id, ownerId));
    }

    // ------------------------------------------------------------------ public: view / pay / status

    public Map<String, Object> publicView(String slug) {
        Object[] row = loadBySlug(slug);
        String status = String.valueOf(row[12]);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", row[2]);
        m.put("title", row[3]);
        m.put("note", row[4]);
        m.put("status", status);
        m.put("payeeName", row[10]);
        m.put("payeeAccount", "BANK".equals(row[7]) ? maskAccount(String.valueOf(row[9])) : maskPhone(String.valueOf(row[9])));
        m.put("payeeBank", row[11]);
        m.put("payeeType", row[7]);
        if (row[5] != null) {
            long amount = ((Number) row[5]).longValue();
            ConversionFee fee = ConversionFee.calculate(amount);
            m.put("amountMinor", amount);
            m.put("feeMinor", fee.feeValue());
            m.put("totalMinor", fee.totalChargedValue());
        } else {
            m.put("amountMinor", null);
        }
        m.put("maxMinor", guestMaxMinor);
        m.put("reusable", row[6]);
        m.put("providers", List.of(
                Map.of("id", "m17", "name", "Orange Money"),
                Map.of("id", "m18", "name", "Africell Money")));
        return m;
    }

    public Map<String, Object> startPayment(String slug, String ip, StartRequest b) {
        limitStarts(ip);
        safety.requireOpenForCustomers();
        Object[] row = loadBySlug(slug);
        String status = String.valueOf(row[12]);
        if (!"ACTIVE".equals(status)) throw new IllegalStateException(inactiveMessage(status));
        UUID requestId = UUID.fromString(String.valueOf(row[0]));
        UUID ownerId = UUID.fromString(String.valueOf(row[1]));
        User owner = users.findById(ownerId).orElse(null);
        if (owner == null || !owner.isActive() || owner.isLocked() || !owner.isEmailVerified()) {
            throw new IllegalStateException("This payment link cannot take payments right now.");
        }

        long minor;
        if (row[5] != null) {
            minor = ((Number) row[5]).longValue();
        } else {
            BigDecimal a = b.getAmount();
            if (a == null || a.compareTo(BigDecimal.ONE) < 0 || a.scale() > 2) {
                throw new IllegalArgumentException("Enter an amount of at least 1.00 (up to 2 decimal places).");
            }
            minor = a.movePointRight(2).longValueExact();
        }
        if (minor > guestMaxMinor) throw new IllegalArgumentException("The most you can pay on one payment link is SLE " + money(guestMaxMinor) + ".");

        String providerId = b.getProviderId() == null ? "" : b.getProviderId().trim();
        String phone = b.getPhone() == null ? "" : b.getPhone().trim();
        if (!MOMO_PROVIDER.matcher(providerId).matches()) throw new IllegalArgumentException("Choose Orange Money or Africell Money.");
        if (!PHONE.matcher(phone).matches()) throw new IllegalArgumentException("Enter your number as +232XXXXXXXX.");
        String payerName = clean(b.getPayerName(), 40);

        long paid24h = count("SELECT COALESCE(SUM(t.amount_value), 0) FROM transactions t WHERE t.payment_request_id IS NOT NULL AND t.source_phone = :p "
                + "AND t.created_at > now() - interval '24 hours' AND t.status NOT IN ('CANCELED', 'FAILED', 'REFUNDED', 'REFUND_PENDING')", "p", phone);
        if (paid24h + minor > guestDailyMinor) {
            throw new IllegalArgumentException("This number has reached its payment limit for today. Please try again tomorrow.");
        }
        if (!Boolean.TRUE.equals(row[6])) {
            long busy = count("SELECT COUNT(*) FROM transactions t WHERE t.payment_request_id = :r AND (t.status IN ('PAID_IN', 'PAYING_OUT', 'COMPLETED') "
                    + "OR (t.status = 'AWAITING_PAYMENT' AND t.created_at > now() - interval '15 minutes' AND t.source_phone <> :p))", "r", requestId, "p", phone);
            if (busy > 0) throw new IllegalStateException("Someone is already paying this link. If it fails, you can try again in a few minutes.");
        }

        boolean bank = "BANK".equals(row[7]);
        Transaction t = transactions.initiateForPaymentLink(new PaymentLinkPayment(
                ownerId, requestId, bank, String.valueOf(row[8]), String.valueOf(row[9]), String.valueOf(row[10]),
                row[11] == null ? null : String.valueOf(row[11]), minor, providerId, phone, payerName.isEmpty() ? null : payerName));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", t.getId().toString());
        m.put("reference", t.getTransactionCode());
        m.put("ussdCode", t.getMonimeUssdCode());
        m.put("amountMinor", t.getAmountValue());
        m.put("feeMinor", t.getFeeValue());
        m.put("totalMinor", t.getTotalChargedValue());
        long ageSeconds = Math.max(0, Duration.between(t.getCreatedAt(), Instant.now()).getSeconds());
        m.put("expiresInSeconds", Math.max(0, 600 - ageSeconds));
        m.put("payeeName", row[10]);
        return m;
    }

    public Map<String, Object> publicStatus(String slug, UUID paymentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT t.status, t.transaction_code, t.amount_value, t.total_charged_value, CAST(EXTRACT(EPOCH FROM t.created_at) * 1000 AS bigint), "
                        + "CAST(EXTRACT(EPOCH FROM t.updated_at) * 1000 AS bigint), r.dest_holder_name "
                        + "FROM transactions t JOIN payment_requests r ON r.id = t.payment_request_id "
                        + "WHERE t.id = :id AND r.slug = :s").setParameter("id", paymentId).setParameter("s", slug == null ? "" : slug).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Payment not found.");
        Object[] x = rows.get(0);
        transactions.nudgeLinkPayment(paymentId);
        String s = String.valueOf(x[0]);
        String phase;
        String message;
        switch (s) {
            case "AWAITING_PAYMENT":
                phase = "waiting";
                message = "Waiting for your payment. Dial the code on your phone.";
                break;
            case "PAID_IN":
            case "PAYING_OUT":
                phase = "sending";
                message = "Payment received. Sending the money now.";
                break;
            case "COMPLETED":
                phase = "done";
                message = "Done. The money has been sent.";
                break;
            case "PAYOUT_FAILED":
                phase = "delayed";
                message = "Your payment was received, but the delivery is delayed. Your money is safe. Quote the reference to support if it takes long.";
                break;
            case "REFUND_PENDING":
            case "REFUNDED":
            case "NEEDS_REVIEW":
                phase = "refund";
                message = "This payment could not be delivered, so your money is being returned to you.";
                break;
            default:
                phase = "failed";
                message = "This payment was not completed. If money left your account, contact support with the reference.";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("message", message);
        m.put("reference", x[1]);
        m.put("amountMinor", ((Number) x[2]).longValue());
        m.put("totalMinor", ((Number) x[3]).longValue());
        m.put("createdAt", ((Number) x[4]).longValue());
        m.put("updatedAt", ((Number) x[5]).longValue());
        m.put("payeeName", x[6]);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> ownerView(Object[] row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(row[0]));
        m.put("slug", row[2]);
        m.put("link", baseUrl + "/p/" + row[2]);
        m.put("title", row[3]);
        m.put("note", row[4]);
        m.put("amountMinor", row[5] == null ? null : ((Number) row[5]).longValue());
        m.put("reusable", row[6]);
        m.put("destType", row[7]);
        m.put("destProviderId", row[8]);
        m.put("destAccount", "BANK".equals(row[7]) ? maskAccount(String.valueOf(row[9])) : maskPhone(String.valueOf(row[9])));
        m.put("destHolderName", row[10]);
        m.put("destBankName", row[11]);
        m.put("status", row[12]);
        m.put("expiresAt", row[13] == null ? null : ((Number) row[13]).longValue());
        m.put("createdAt", ((Number) row[14]).longValue());
        m.put("paidCount", ((Number) row[15]).longValue());
        m.put("receivedMinor", ((Number) row[16]).longValue());
        m.put("inProgress", ((Number) row[17]).longValue());
        return m;
    }

    private Object[] loadOne(String where, UUID value, UUID ownerId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS + " FROM payment_requests r WHERE " + where + " AND r.owner_user_id = :o")
                .setParameter("v", value).setParameter("o", ownerId).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Payment link not found.");
        return rows.get(0);
    }

    private Object[] loadBySlug(String slug) {
        String s = slug == null ? "" : slug.trim().toLowerCase();
        if (s.length() < 4 || s.length() > 16) throw new IllegalArgumentException("This payment link was not found.");
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS + " FROM payment_requests r WHERE r.slug = :s")
                .setParameter("s", s).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("This payment link was not found.");
        return rows.get(0);
    }

    private long count(String sql, Object... kv) {
        jakarta.persistence.Query q = em.createNativeQuery(sql);
        for (int i = 0; i + 1 < kv.length; i += 2) q.setParameter(String.valueOf(kv[i]), kv[i + 1]);
        return ((Number) q.getSingleResult()).longValue();
    }

    private String newSlug() {
        for (int attempt = 0; attempt < 12; attempt++) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.append(SLUG_CHARS.charAt(RANDOM.nextInt(SLUG_CHARS.length())));
            String s = sb.toString();
            if (count("SELECT COUNT(*) FROM payment_requests WHERE slug = :s", "s", s) == 0) return s;
        }
        throw new IllegalStateException("Could not create a payment link right now. Please try again.");
    }

    /** At most 12 payment starts from one IP in 10 minutes (phones on one mobile network may share an IP, so this is generous). */
    private void limitStarts(String ip) {
        long now = System.currentTimeMillis();
        if (starts.size() > 20000) starts.clear();
        Deque<Long> q = starts.computeIfAbsent(ip == null || ip.isBlank() ? "?" : ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 600_000L) q.pollFirst();
            if (q.size() >= 12) throw new TooManyRequestsException("Too many payment attempts. Please wait a few minutes and try again.");
            q.addLast(now);
        }
    }

    private static String inactiveMessage(String status) {
        switch (status) {
            case "PAID": return "This payment link has already been paid.";
            case "EXPIRED": return "This payment link has expired.";
            case "CLOSED": return "This payment link is closed.";
            default: return "This payment link is not accepting payments.";
        }
    }

    private static String ownerState(String status) {
        switch (status) {
            case "COMPLETED": return "received";
            case "PAID_IN":
            case "PAYING_OUT": return "sending";
            case "PAYOUT_FAILED": return "delayed";
            case "REFUND_PENDING":
            case "REFUNDED":
            case "NEEDS_REVIEW": return "refunded";
            case "AWAITING_PAYMENT": return "waiting";
            default: return "failed";
        }
    }

    private static String clean(String v, int max) {
        if (v == null) return "";
        String c = v.replaceAll("[\\p{Cntrl}]", " ").trim().replaceAll("\\s+", " ");
        return c.length() > max ? c.substring(0, max).trim() : c;
    }

    private static String money(long minor) {
        return BigDecimal.valueOf(minor).movePointLeft(2).toPlainString();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 4) + " ••• ••" + phone.substring(phone.length() - 3);
    }

    private static String maskAccount(String acct) {
        if (acct == null || acct.length() < 5) return acct;
        return "•••• " + acct.substring(acct.length() - 4);
    }

    // ------------------------------------------------------------------ request bodies

    public static class CreateRequest {
        private String title;
        private String note;
        private BigDecimal amount;
        private Boolean reusable;
        private Integer expiresInDays;
        private String destType;
        private String providerId;
        private String phone;
        private String bankProviderId;
        private String accountNumber;
        private String verificationToken;

        public String getTitle() { return title; }
        public void setTitle(String v) { this.title = v; }
        public String getNote() { return note; }
        public void setNote(String v) { this.note = v; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal v) { this.amount = v; }
        public Boolean getReusable() { return reusable; }
        public void setReusable(Boolean v) { this.reusable = v; }
        public Integer getExpiresInDays() { return expiresInDays; }
        public void setExpiresInDays(Integer v) { this.expiresInDays = v; }
        public String getDestType() { return destType; }
        public void setDestType(String v) { this.destType = v; }
        public String getProviderId() { return providerId; }
        public void setProviderId(String v) { this.providerId = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
        public String getBankProviderId() { return bankProviderId; }
        public void setBankProviderId(String v) { this.bankProviderId = v; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String v) { this.accountNumber = v; }
        public String getVerificationToken() { return verificationToken; }
        public void setVerificationToken(String v) { this.verificationToken = v; }
    }

    public static class StartRequest {
        private String providerId;
        private String phone;
        private BigDecimal amount;
        private String payerName;

        public String getProviderId() { return providerId; }
        public void setProviderId(String v) { this.providerId = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal v) { this.amount = v; }
        public String getPayerName() { return payerName; }
        public void setPayerName(String v) { this.payerName = v; }
    }
}
