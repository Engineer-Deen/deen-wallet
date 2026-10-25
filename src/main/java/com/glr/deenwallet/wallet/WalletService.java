package com.glr.deenwallet.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.glr.deenwallet.monime.CreatePayoutRequest;
import com.glr.deenwallet.monime.MonimeClient;
import com.glr.deenwallet.monime.Money;
import com.glr.deenwallet.monime.PayoutResult;
import com.glr.deenwallet.monime.ProviderKycResult;
import com.glr.deenwallet.transaction.BankAccountVerificationResponse;
import com.glr.deenwallet.transaction.BankOptionResponse;
import com.glr.deenwallet.transaction.BankTransferService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Wallet screen of the admin app.
 *
 * SUPER_ADMIN: sees the real Monime balance and every payment and payout, can withdraw from the Monime
 *              account (mobile money or bank) and can transfer money to an admin's wallet.
 * ADMIN:       has a separate wallet (a ledger inside the same Monime account). A super admin pays into
 *              it; the admin can only withdraw from it.
 *
 * Money rules
 *   - Admin wallets are only a record of what is owed. The money stays in the Monime account.
 *   - A super admin can only transfer / withdraw what is NOT already owed to admin wallets or on its way out:
 *         withdrawable = Monime available - sum(admin wallets) - pending withdrawals.
 *   - A withdrawal first writes the debit and a PENDING row in ONE database transaction, then calls Monime
 *     with the idempotency key "wallet-wd-{id}". A definite rejection puts the money back; an unclear answer
 *     (timeout, 5xx) is retried with the same key by WalletReconcileJob, so a payout can never be sent twice.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    static final String CURRENCY = "SLE";
    private static final long MIN_MINOR = 100;                    // SLE 1.00
    private static final long MAX_MINOR = 10_000_000_000L;        // SLE 100,000,000.00
    private static final long ADVISORY_LOCK = 7_340_035L;
    /** Monime charges about 1% on every payout (same share the conversion fee already assumes). */
    static final String FEE_PERCENT_TEXT = "1%";
    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", java.util.Locale.ENGLISH)
                    .withZone(java.time.ZoneOffset.UTC);

    /** Monime payout fee for an amount, in minor units: 1%, rounded half up. */
    static long feeOf(long minor) {
        return (minor + 50) / 100;
    }
    static final Map<String, String> MOMO_PROVIDERS = new LinkedHashMap<>();
    static {
        MOMO_PROVIDERS.put("m17", "Orange Money");
        MOMO_PROVIDERS.put("m18", "Africell");
    }

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;
    private final WalletMonimeApi monimeApi;
    private final MonimeClient monimeClient;
    private final com.glr.deenwallet.monime.MonimeProperties monimeProps;
    private final BankTransferService banks;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final WalletMailer emails;

    public WalletService(TransactionTemplate tx, WalletMonimeApi monimeApi, MonimeClient monimeClient,
                         com.glr.deenwallet.monime.MonimeProperties monimeProps, BankTransferService banks,
                         UserRepository users, PasswordEncoder passwordEncoder, WalletMailer emails) {
        this.tx = tx;
        this.monimeApi = monimeApi;
        this.monimeClient = monimeClient;
        this.monimeProps = monimeProps;
        this.banks = banks;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.emails = emails;
    }

    // ------------------------------------------------------------------ views

    public record Summary(String role, boolean superAdmin, boolean configured, String error,
                          Long monimeAvailable, long heldForAdmins, long pendingOut, Long withdrawable,
                          long myBalance, long myPending, String currency) { }

    public record AdminRow(UUID id, String name, String email, long balance) { }

    public record Activity(String kind, String id, Instant at, long amount, String status, String ownerName,
                           String detail, String reason) { }

    public record Detail(String label, String value) { }

    public record TxRow(String id, String type, long amount, String timestamp, String reference, Long balanceAfter,
                        String label, String detail, String dwCode, String customer, String status,
                        Long fee, List<Detail> details) { }

    public record TxPage(List<TxRow> rows, String next, String error) { }

    public record Options(List<Map<String, String>> momo, List<Map<String, String>> banks, String feePercent) { }

    public record Lookup(boolean ok, String holderName, String message) { }

    // ------------------------------------------------------------------ summary

    public Summary summary(User me) {
        boolean isSuper = isSuper(me);
        boolean configured = monimeApi.configured();
        String error = null;
        Long monime = null;
        if (isSuper) {
            if (!configured) {
                error = "The payment account is not set up on the server.";
            } else {
                try {
                    monime = monimeApi.availableBalance(false);
                } catch (IllegalStateException e) {
                    error = e.getMessage();
                }
            }
        }
        long held = scalar("SELECT COALESCE(SUM(balance_value), 0) FROM admin_wallets");
        long pending = scalar("SELECT COALESCE(SUM(amount_value + fee_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
        Long withdrawable = monime == null ? null : Math.max(0, monime - held - pending);
        long mine = 0;
        long minePending = 0;
        if (!isSuper) {
            mine = scalar("SELECT COALESCE((SELECT balance_value FROM admin_wallets WHERE user_id = :u), 0)", me.getId());
            minePending = scalar("SELECT COALESCE(SUM(amount_value), 0) FROM wallet_withdrawals "
                    + "WHERE owner_user_id = :u AND status IN ('PENDING', 'REVIEW')", me.getId());
        }
        return new Summary(me.getRole(), isSuper, configured, error, monime, isSuper ? held : 0, isSuper ? pending : 0,
                withdrawable, mine, minePending, CURRENCY);
    }

    // ------------------------------------------------------------------ live Monime lists (super admin)

    public TxPage transactions(String type, String after) {
        return transactions(type, after, 20);
    }

    /**
     * Searches the latest payments or payouts (up to 600) for a DeenWallet reference, a payment-service reference,
     * a customer name, phone or email.
     */
    public TxPage search(String type, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.isEmpty()) return transactions(type, null, 20);
        List<TxRow> found = new ArrayList<>();
        String cursor = null;
        String error = null;
        for (int page = 0; page < 12; page++) {
            TxPage p = transactions(type, cursor, 50);
            if (p.error() != null) { error = p.error(); break; }
            for (TxRow r : p.rows()) {
                if (rowMatches(r, needle)) found.add(r);
            }
            cursor = p.next();
            if (cursor == null) break;
        }
        return new TxPage(found, null, found.isEmpty() ? error : null);
    }

    private static boolean rowMatches(TxRow r, String needle) {
        if (contains(r.id(), needle) || contains(r.reference(), needle) || contains(r.dwCode(), needle)
                || contains(r.customer(), needle)) return true;
        if (r.details() != null) {
            for (Detail d : r.details()) {
                if (contains(d.value(), needle)) return true;
            }
        }
        return false;
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.toLowerCase().contains(needle);
    }

    /** Short reference shown to admins for a wallet withdrawal (also used in the email). */
    static String refOf(UUID id) {
        return "WD-" + id.toString().substring(0, 8).toUpperCase();
    }

    private static final java.util.regex.Pattern UUID_RE =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Any DeenWallet transaction id the payment service echoed back inside the ownership graph or metadata. */
    private static void collectUuids(JsonNode n, Set<String> out) {
        String text = n.path("ownershipGraph").toString() + " " + n.path("metadata").toString();
        var m = UUID_RE.matcher(text);
        while (m.find()) out.add(m.group().toLowerCase());
    }

    private TxPage transactions(String type, String after, int limit) {
        JsonNode root;
        try {
            root = monimeApi.transactions(type, after, limit);
        } catch (IllegalStateException e) {
            return new TxPage(List.of(), null, e.getMessage());
        }
        JsonNode result = root.path("result");
        List<JsonNode> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (result.isArray()) {
            for (JsonNode n : result) {
                items.add(n);
                collectOwnerIds(n.path("ownershipGraph").path("owner"), ids, 0);
                collectUuids(n, ids);
            }
        }
        Set<String> refs = new HashSet<>();
        for (JsonNode n : items) {
            String r = text(n.path("reference"));
            if (r != null) refs.add(r);
        }
        Map<String, String> payoutByRef = payoutIdsByReference(refs);
        ids.addAll(payoutByRef.values());
        Map<String, Match> matches = match(ids);
        List<Draft> drafts = new ArrayList<>();
        for (JsonNode n : items) {
            Draft d = new Draft();
            d.id = n.path("id").asText("");
            d.kind = n.path("type").asText("");
            d.amount = n.path("amount").path("value").asLong(0);
            d.ts = n.path("timestamp").asText(null);
            d.ref = n.path("reference").asText(null);
            JsonNode after1 = n.path("financialAccount").path("balance").path("after").path("value");
            d.balanceAfter = after1.isNumber() ? after1.asLong() : null;
            d.topOwner = text(n.path("ownershipGraph").path("owner").path("id"));
            String feeCode = text(n.path("originatingFee").path("code"));
            String reversal = text(n.path("originatingReversal").path("originTxnId"));
            if (reversal == null) reversal = text(n.path("originatingReversal").path("originTxnRef"));
            Set<String> owners = new HashSet<>();
            collectOwnerIds(n.path("ownershipGraph").path("owner"), owners, 0);
            collectUuids(n, owners);
            if (d.ref != null && payoutByRef.containsKey(d.ref)) owners.add(payoutByRef.get(d.ref));
            String ownerType = firstOwnerType(n.path("ownershipGraph").path("owner"));
            Match m = null;
            for (String o : owners) {
                if (matches.containsKey(o)) { m = matches.get(o); break; }
            }
            boolean credit = "credit".equals(d.kind);
            d.details = new ArrayList<>();
            if (feeCode != null) {
                d.isFee = true;
                d.label = "Service fee";
                d.detail = feeCode;
            } else if (reversal != null) {
                d.label = "Reversal";
            } else if (m != null && m.withdrawalOwner != null) {
                // An admin / super admin withdrawal made from the wallet screen.
                d.label = "Withdrawal";
                d.dwCode = m.code;
                d.detail = m.withdrawalOwner;
                d.customer = m.withdrawalOwner;
                d.status = m.withdrawalStatus;
                d.fee = m.withdrawalFee;
                d.freeWd = m.freeWd;
                d.details.addAll(m.base);
            } else if (m != null) {
                // A customer conversion: the payment in, or the payout out to the receiver.
                d.label = credit ? "Customer payment" : (m.refund ? "Refund to customer" : "Money out");
                d.dwCode = m.code;
                d.customer = m.customer;
                d.status = m.status;
                d.detail = credit ? m.service : (m.refund ? m.service : "customer conversion");
                d.details.addAll(m.base);
                Long f = credit ? m.depositFee : m.payoutFee;
                if (f != null && !m.refund) d.fee = f;
            } else if (ownerType != null && ownerType.contains("internal_transfer")) {
                d.label = "Internal transfer";
            } else {
                d.label = credit ? "Payment received" : "Money sent out";
                if (ownerType != null) d.detail = ownerType.replace('_', ' ');
            }
            drafts.add(d);
        }
        // A separate Monime fee line that belongs to a payout on this page is folded into that payout.
        for (Draft f : drafts) {
            if (!f.isFee || f.topOwner == null) continue;
            for (Draft o : drafts) {
                if (o == f || o.isFee || !"debit".equals(o.kind) || !f.topOwner.equals(o.topOwner)) continue;
                o.foldedFee += f.amount;
                f.folded = true;
                break;
            }
        }
        List<TxRow> rows = new ArrayList<>();
        for (Draft d : drafts) {
            if (d.folded) continue;
            Long fee = d.freeWd ? Long.valueOf(0) : (d.foldedFee > 0 ? Long.valueOf(d.foldedFee) : d.fee);
            List<Detail> details = new ArrayList<>(d.details);
            if (d.freeWd) {
                details.add(new Detail("Service fee", "Free"));
            } else if (fee != null && !d.isFee) {
                details.add(new Detail("Service fee", money(fee)));
                if ("debit".equals(d.kind)) details.add(new Detail("Total taken from the balance", money(d.amount + fee)));
            }
            if (d.isFee && d.detail != null) details.add(new Detail("Fee type", d.detail));
            if (d.balanceAfter != null) details.add(new Detail("Account balance after", money(d.balanceAfter)));
            if (d.ref != null) details.add(new Detail("Monime reference", d.ref));
            rows.add(new TxRow(d.id, d.kind, d.amount, d.ts, d.ref, d.balanceAfter, d.label, d.detail, d.dwCode,
                    d.customer, d.status, fee, details));
        }
        String next = text(root.path("pagination").path("next"));
        return new TxPage(rows, next, null);
    }

    /** payout ids (as known to the payment service) for the given financial-transaction references. */
    @SuppressWarnings("unchecked")
    private Map<String, String> payoutIdsByReference(Set<String> refs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (refs.isEmpty()) return out;
        try {
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT txn_reference, payout_id FROM wallet_payout_refs WHERE txn_reference IN (:refs)")
                    .setParameter("refs", refs).getResultList();
            for (Object[] r : rows) out.put((String) r[0], (String) r[1]);
        } catch (RuntimeException e) {
            log.warn("Wallet: reference lookup failed: {}", e.toString());
        }
        return out;
    }

    /**
     * Learns which payment-service reference belongs to which payout (customer payouts and wallet withdrawals), a few
     * per run, newest first. This is what lets a line in the Payouts list show its DeenWallet reference.
     * Called by WalletReconcileJob.
     */
    @SuppressWarnings("unchecked")
    void syncPayoutRefs() {
        if (!monimeApi.configured()) return;
        List<Object> pending = em.createNativeQuery(
                "SELECT p.pid FROM ("
                        + "SELECT monime_payout_id AS pid, created_at FROM wallet_withdrawals WHERE monime_payout_id IS NOT NULL "
                        + "UNION ALL SELECT monime_payout_id, created_at FROM transactions WHERE monime_payout_id IS NOT NULL AND created_at > now() - interval '45 days' "
                        + "UNION ALL SELECT refund_payout_id, created_at FROM transactions WHERE refund_payout_id IS NOT NULL AND created_at > now() - interval '45 days'"
                        + ") p LEFT JOIN wallet_payout_refs r ON r.payout_id = p.pid "
                        + "WHERE r.payout_id IS NULL OR (r.txn_reference IS NULL AND r.attempts < 10 AND r.last_try < now() - interval '2 minutes') "
                        + "ORDER BY p.created_at DESC LIMIT 20").getResultList();
        for (Object o : pending) {
            String payoutId = String.valueOf(o);
            String ref;
            try {
                ref = monimeApi.payoutReference(payoutId);
            } catch (RuntimeException e) {
                log.debug("Wallet: could not read the reference of payout {}: {}", payoutId, e.toString());
                continue;
            }
            final String finalRef = ref;
            tx.executeWithoutResult(st -> {
                if (finalRef != null) {
                    em.createNativeQuery("INSERT INTO wallet_payout_refs (payout_id, txn_reference, attempts, last_try) VALUES (:p, :r, 1, now()) "
                                    + "ON CONFLICT (payout_id) DO UPDATE SET txn_reference = EXCLUDED.txn_reference, attempts = wallet_payout_refs.attempts + 1, last_try = now()")
                            .setParameter("p", payoutId).setParameter("r", finalRef).executeUpdate();
                } else {
                    em.createNativeQuery("INSERT INTO wallet_payout_refs (payout_id, attempts, last_try) VALUES (:p, 1, now()) "
                                    + "ON CONFLICT (payout_id) DO UPDATE SET attempts = wallet_payout_refs.attempts + 1, last_try = now()")
                            .setParameter("p", payoutId).executeUpdate();
                }
            });
        }
    }

    private static final class Draft {
        String id, kind, ts, ref, label, detail, dwCode, customer, status, topOwner;
        long amount;
        Long balanceAfter;
        Long fee;
        long foldedFee;
        boolean isFee, folded, freeWd;
        List<Detail> details;
    }

    private record Match(String code, String customer, String service, String status, boolean refund,
                         String withdrawalOwner, String withdrawalStatus, List<Detail> base,
                         Long depositFee, Long payoutFee, Long withdrawalFee, boolean freeWd) { }

    private static void add(List<Detail> list, String label, Object value) {
        if (value == null) return;
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equals(s)) return;
        list.add(new Detail(label, s));
    }

    private static String pretty(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).replace('_', ' ').toLowerCase();
        return s.isEmpty() ? null : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String when(Object ts) {
        Instant i = instant(ts);
        return i == null ? null : WHEN.format(i);
    }

    private static Long minor(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Match> match(Set<String> ids) {
        Map<String, Match> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        List<UUID> uuids = new ArrayList<>();
        for (String s : ids) {
            if (UUID_RE.matcher(s).matches()) {
                try { uuids.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
            }
        }
        var tq = em.createNativeQuery(
                        "SELECT t.monime_payment_code_id, t.monime_payout_id, t.refund_payout_id, t.transaction_code, "
                                + "u.full_name, t.service_type, t.status, u.email, u.phone, t.source_provider_id, t.source_phone, "
                                + "t.amount_value, t.fee_value, t.total_charged_value, t.monime_deposit_fee_value, "
                                + "t.monime_withdrawal_fee_value, t.destination_provider_id, t.destination_phone, "
                                + "t.destination_holder_name, t.destination_bank_name, t.destination_bank_account_number, "
                                + "t.destination_bank_holder_name, t.created_at, t.paid_in_at, t.failure_reason, t.id "
                                + "FROM transactions t LEFT JOIN users u ON u.id = t.user_id "
                                + "WHERE t.monime_payment_code_id IN (:ids) OR t.monime_payout_id IN (:ids) OR t.refund_payout_id IN (:ids)"
                                + (uuids.isEmpty() ? "" : " OR t.id IN (:uuids)"))
                .setParameter("ids", ids);
        if (!uuids.isEmpty()) tq.setParameter("uuids", uuids);
        List<Object[]> t = tq.getResultList();
        for (Object[] r : t) {
            String pay = (String) r[0];
            String payout = (String) r[1];
            String refund = (String) r[2];
            String code = (String) r[3];
            String name = (String) r[4];
            String service = r[5] == null ? null : String.valueOf(r[5]).replace('_', ' ').toLowerCase();
            String status = r[6] == null ? null : String.valueOf(r[6]);
            List<Detail> base = new ArrayList<>();
            add(base, "Customer", name);
            add(base, "Customer email", r[7]);
            add(base, "Customer phone", r[8]);
            add(base, "DeenWallet reference", code);
            add(base, "Service", pretty(r[5]));
            add(base, "Status", pretty(r[6]));
            String from = r[9] == null && r[10] == null ? null
                    : (r[9] == null ? "" : MOMO_PROVIDERS.getOrDefault((String) r[9], (String) r[9]) + " ") + (r[10] == null ? "" : r[10]);
            add(base, "Paid from", from);
            if (r[19] != null) {
                add(base, "Sent to", r[19] + " " + (r[20] == null ? "" : r[20]) + (r[21] == null ? "" : " (" + r[21] + ")"));
            } else if (r[17] != null) {
                add(base, "Sent to", (r[16] == null ? "" : MOMO_PROVIDERS.getOrDefault((String) r[16], (String) r[16]) + " ")
                        + r[17] + (r[18] == null ? "" : " (" + r[18] + ")"));
            }
            Long amt = minor(r[11]);
            Long chargedFee = minor(r[12]);
            Long total = minor(r[13]);
            if (amt != null) add(base, "Amount sent to receiver", money(amt));
            if (chargedFee != null) add(base, "Fee charged to customer", money(chargedFee));
            if (total != null) add(base, "Total paid by customer", money(total));
            add(base, "Created", when(r[22]));
            add(base, "Payment received", when(r[23]));
            add(base, "Failure reason", r[24]);
            Match m = new Match(code, name, service, status, false, null, null, base, minor(r[14]), minor(r[15]), null, false);
            Match mRefund = new Match(code, name, service, status, true, null, null, base, minor(r[14]), minor(r[15]), null, false);
            if (pay != null) out.putIfAbsent(pay, m);
            if (payout != null) out.putIfAbsent(payout, m);
            if (refund != null) out.putIfAbsent(refund, mRefund);
            if (r[25] != null) out.putIfAbsent(String.valueOf(r[25]).toLowerCase(), m);
        }
        List<Object[]> w = em.createNativeQuery(
                        "SELECT w.monime_payout_id, u.full_name, w.status, w.method, w.provider_id, w.destination, w.holder_name, "
                                + "w.amount_value, w.fee_value, w.created_at, w.failure_reason, u.email, w.id, w.scope FROM wallet_withdrawals w "
                                + "LEFT JOIN users u ON u.id = w.owner_user_id WHERE w.monime_payout_id IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : w) {
            String owner = r[1] == null ? "Admin" : (String) r[1];
            List<Detail> base = new ArrayList<>();
            String wref = r[12] == null ? null : refOf((UUID) r[12]);
            add(base, "DeenWallet reference", wref);
            add(base, "Withdrawn by", owner);
            add(base, "Admin email", r[11]);
            add(base, "Status", pretty(r[2]));
            String dest = ("momo".equals(r[3]) ? MOMO_PROVIDERS.getOrDefault((String) r[4], "Mobile money") : "Bank") + " " + r[5];
            add(base, "Sent to", dest + (r[6] == null ? "" : " (" + r[6] + ")"));
            add(base, "Amount", money(((Number) r[7]).longValue()));
            add(base, "Requested", when(r[9]));
            add(base, "Failure reason", r[10]);
            out.put((String) r[0], new Match(wref, null, null, null, false, owner, (String) r[2], base, null, null,
                    "ADMIN".equals(r[13]) ? Long.valueOf(0) : (minor(r[8]) != null && minor(r[8]) > 0 ? minor(r[8]) : null),
                    "ADMIN".equals(r[13])));
        }
        return out;
    }

    private static void collectOwnerIds(JsonNode owner, Set<String> out, int depth) {
        if (owner == null || owner.isMissingNode() || owner.isNull() || depth > 5) return;
        String id = text(owner.path("id"));
        if (id != null) out.add(id);
        collectOwnerIds(owner.path("owner"), out, depth + 1);
    }

    private static String firstOwnerType(JsonNode owner) {
        for (int i = 0; i < 6 && owner != null && !owner.isMissingNode() && !owner.isNull(); i++) {
            String t = text(owner.path("type"));
            if (t != null) return t.toLowerCase();
            owner = owner.path("owner");
        }
        return null;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------ activity, admins, options, lookup

    @SuppressWarnings("unchecked")
    public List<Activity> activity(User me) {
        boolean isSuper = isSuper(me);
        List<Activity> out = new ArrayList<>();
        String scopeW = isSuper ? "" : "WHERE w.owner_user_id = :u ";
        var wq = em.createNativeQuery(
                "SELECT w.id, w.created_at, w.amount_value, w.status, u.full_name, w.method, w.provider_id, w.destination, "
                        + "w.holder_name, w.failure_reason, w.scope FROM wallet_withdrawals w "
                        + "LEFT JOIN users u ON u.id = w.owner_user_id " + scopeW + "ORDER BY w.created_at DESC LIMIT 50");
        if (!isSuper) wq.setParameter("u", me.getId());
        for (Object[] r : (List<Object[]>) wq.getResultList()) {
            String detail = ("momo".equals(r[5]) ? MOMO_PROVIDERS.getOrDefault((String) r[6], "Mobile money") : "Bank")
                    + " " + r[7] + (r[8] == null ? "" : " (" + r[8] + ")");
            out.add(new Activity("withdrawal", String.valueOf(r[0]), instant(r[1]), ((Number) r[2]).longValue(),
                    (String) r[3], r[4] == null ? "Admin" : (String) r[4], detail, (String) r[9]));
        }
        String scopeE = isSuper ? "" : "AND e.user_id = :u ";
        var eq = em.createNativeQuery(
                "SELECT e.id, e.created_at, e.amount_value, u.full_name, e.counterparty_name, e.note FROM wallet_entries e "
                        + "LEFT JOIN users u ON u.id = e.user_id WHERE e.kind = 'TRANSFER_IN' " + scopeE
                        + "ORDER BY e.created_at DESC LIMIT 50");
        if (!isSuper) eq.setParameter("u", me.getId());
        for (Object[] r : (List<Object[]>) eq.getResultList()) {
            String from = r[4] == null ? "Super admin" : (String) r[4];
            String detail = isSuper ? "Transfer to " + (r[3] == null ? "admin" : r[3])
                    : "Received from " + from;
            if (r[5] != null && !String.valueOf(r[5]).isBlank()) detail += " - " + r[5];
            out.add(new Activity("transfer", String.valueOf(r[0]), instant(r[1]), ((Number) r[2]).longValue(),
                    "COMPLETED", r[3] == null ? "Admin" : (String) r[3], detail, null));
        }
        out.sort(Comparator.comparing(Activity::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > 60 ? new ArrayList<>(out.subList(0, 60)) : out;
    }

    @SuppressWarnings("unchecked")
    public List<AdminRow> admins() {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT u.id, u.full_name, u.email, COALESCE(w.balance_value, 0) FROM users u "
                        + "LEFT JOIN admin_wallets w ON w.user_id = u.id WHERE u.role = 'ADMIN' AND u.active = true "
                        + "ORDER BY u.full_name").getResultList();
        List<AdminRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new AdminRow((UUID) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue()));
        }
        return out;
    }

    public Options options() {
        List<Map<String, String>> momo = new ArrayList<>();
        MOMO_PROVIDERS.forEach((id, name) -> momo.add(Map.of("id", id, "name", name)));
        List<Map<String, String>> bankList = new ArrayList<>();
        try {
            for (BankOptionResponse b : banks.listAvailableBanks()) {
                if (b.available()) bankList.add(Map.of("id", b.providerId(), "name", b.name()));
            }
        } catch (RuntimeException e) {
            log.warn("Wallet: could not load the bank list: {}", e.toString());
        }
        return new Options(momo, bankList, FEE_PERCENT_TEXT);
    }

    public Lookup lookup(User me, String method, String providerId, String destination) {
        try {
            Dest d = resolveDestination(me, method, providerId, destination);
            return new Lookup(true, d.holder, null);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Lookup(false, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Lookup(false, null, "The account name could not be checked right now.");
        }
    }

    // ------------------------------------------------------------------ transfer (super admin -> admin wallet)

    public Summary transfer(User me, UUID adminId, BigDecimal amount, String note, String pin) {
        requireSuper(me);
        verifyPin(me, pin);
        long minor = toMinor(amount);
        if (adminId == null) throw new IllegalArgumentException("Choose an admin.");
        User target = users.findById(adminId).orElseThrow(() -> new IllegalArgumentException("That admin was not found."));
        if (!"ADMIN".equals(target.getRole()) || !target.isActive()) {
            throw new IllegalArgumentException("Money can only be sent to an active admin account.");
        }
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 300) cleanNote = cleanNote.substring(0, 300);
        if (cleanNote != null && cleanNote.isEmpty()) cleanNote = null;
        final String finalNote = cleanNote;
        long monime = monimeApi.availableBalance(true);
        final AtomicLong newBalance = new AtomicLong();

        tx.executeWithoutResult(st -> {
            lockPlatform();
            long held = scalarTx("SELECT COALESCE(SUM(balance_value), 0) FROM admin_wallets");
            long pending = scalarTx("SELECT COALESCE(SUM(amount_value + fee_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
            long free = monime - held - pending;
            if (minor > free) {
                throw new IllegalArgumentException("Not enough free money in the DeenWallet balance. Available to send: "
                        + money(Math.max(0, free)) + ".");
            }
            long after = credit(target.getId(), minor);
            newBalance.set(after);
            insertEntry(target.getId(), "TRANSFER_IN", minor, after, finalNote, me.getId(), me.getFullName(), null, me.getId());
        });
        log.info("Wallet: {} transferred {} to admin {}", me.getEmail(), money(minor), target.getEmail());
        try {
            emails.sendWalletTransferEmail(target, true, me.getFullName(), money(minor), finalNote, money(newBalance.get()));
            emails.sendWalletTransferEmail(me, false, target.getFullName(), money(minor), finalNote, null);
        } catch (RuntimeException e) {
            log.warn("Wallet: could not queue the transfer emails: {}", e.toString());
        }
        return summary(me);
    }

    // ------------------------------------------------------------------ withdraw (both roles)

    public Summary withdraw(User me, String method, String providerId, String destination, BigDecimal amount, String pin) {
        if (!isSuper(me) && !"ADMIN".equals(me.getRole())) throw new IllegalArgumentException("Only admins can use the wallet.");
        verifyPin(me, pin);
        long minor = toMinor(amount);
        Dest d = resolveDestination(me, method, providerId, destination);
        if (!monimeApi.configured()) throw new IllegalStateException("The payment account is not set up on the server.");
        long monime = monimeApi.availableBalance(true);
        boolean isSuper = isSuper(me);
        UUID id = UUID.randomUUID();
        // Admin withdrawals are free; only the super admin pays the service fee.
        final long fee = isSuper ? feeOf(minor) : 0L;

        tx.executeWithoutResult(st -> {
            if (isSuper) {
                lockPlatform();
                long held = scalarTx("SELECT COALESCE(SUM(balance_value), 0) FROM admin_wallets");
                long pending = scalarTx("SELECT COALESCE(SUM(amount_value + fee_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
                long free = monime - held - pending;
                if (minor + fee > free) {
                    throw new IllegalArgumentException("Not enough free money to withdraw " + money(minor) + " plus the service fee of "
                            + money(fee) + ". Available: " + money(Math.max(0, free))
                            + " (the rest is held for admin wallets or already on its way out).");
                }
            } else {
                // Same platform lock as the super admin path and transfers (always taken BEFORE a wallet row lock),
                // so several admins withdrawing at once cannot together promise more than the account really holds.
                lockPlatform();
                ensureWallet(me.getId());
                long balance = lockedBalance(me.getId());
                if (minor > balance) {
                    throw new IllegalArgumentException("Your wallet balance is " + money(balance) + ".");
                }
                long pendingNow = scalarTx("SELECT COALESCE(SUM(amount_value + fee_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
                if (minor + fee > monime - pendingNow) {
                    throw new IllegalStateException("The payment account is busy or short right now. Please try again in a minute, or contact the super admin.");
                }
                long after = balance - minor;
                em.createNativeQuery("UPDATE admin_wallets SET balance_value = :b, updated_at = now() WHERE user_id = :u")
                        .setParameter("b", after).setParameter("u", me.getId()).executeUpdate();
                insertEntry(me.getId(), "WITHDRAWAL", minor, after, d.label(), null, null, id, me.getId());
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", id);
            w.put("owner_user_id", me.getId());
            w.put("scope", isSuper ? "PLATFORM" : "ADMIN");
            w.put("amount_value", minor);
            w.put("fee_value", fee);
            w.put("method", d.method);
            w.put("provider_id", d.providerId);
            w.put("destination", d.destination);
            w.put("holder_name", d.holder == null || d.holder.isBlank() ? null : (d.holder.length() > 150 ? d.holder.substring(0, 150) : d.holder));
            insertRow("wallet_withdrawals", w);
        });
        log.info("Wallet: withdrawal {} of {} started by {}", id, money(minor), me.getEmail());
        notifyWithdrawal(id, "started");
        submit(id);
        return summary(me);
    }

    /**
     * Sends one PENDING withdrawal to Monime (idempotent) and records what Monime answered.
     * Safe to call again for the same id: the key "wallet-wd-{id}" makes Monime return the same payout.
     */
    void submit(UUID id) {
        Object[] row = loadWithdrawal(id);
        if (row == null) return;
        String status = (String) row[0];
        String existingPayout = (String) row[1];
        if (!"PENDING".equals(status) && !"REVIEW".equals(status)) return;
        if (existingPayout != null) return;
        long amount = ((Number) row[2]).longValue();
        String method = (String) row[3];
        String providerId = (String) row[4];
        String destination = (String) row[5];

        tx.executeWithoutResult(st -> em.createNativeQuery(
                "UPDATE wallet_withdrawals SET attempts = attempts + 1, updated_at = now() WHERE id = :id")
                .setParameter("id", id).executeUpdate());

        CreatePayoutRequest req = "bank".equals(method)
                ? CreatePayoutRequest.toBank(new Money(CURRENCY, amount), providerId, destination, monimeProps.defaultFinancialAccountId())
                : CreatePayoutRequest.toMobileMoney(new Money(CURRENCY, amount), providerId, destination, monimeProps.defaultFinancialAccountId());
        try {
            PayoutResult p = monimeClient.createPayout(req, "wallet-wd-" + id);
            if (p == null || p.id() == null || p.id().isBlank()) {
                log.warn("Wallet: Monime returned no payout id for withdrawal {}; will retry with the same key", id);
                return;
            }
            recordPayout(id, p.id());
            applyPayoutStatus(id, p);
        } catch (ResourceAccessException e) {
            log.warn("Wallet: withdrawal {} timed out; will retry with the same key", id);
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            if (s == 429 || s == 408 || s == 409 || s >= 500) {
                log.warn("Wallet: withdrawal {} got HTTP {} from Monime; will retry with the same key", id, s);
                return;
            }
            String body = e.getResponseBodyAsString();
            if (body != null && body.length() > 200) body = body.substring(0, 200);
            log.error("Wallet: Monime rejected withdrawal {} (HTTP {}): {}", id, s, body);
            failAndReverse(id, "The payment provider rejected this withdrawal (HTTP " + s + ").");
        }
    }

    /** Checks Monime for the real outcome of one open withdrawal. Called by WalletReconcileJob. */
    void reconcile(UUID id) {
        Object[] row = loadWithdrawal(id);
        if (row == null) return;
        String status = (String) row[0];
        String payoutId = (String) row[1];
        if (!"PENDING".equals(status) && !"REVIEW".equals(status)) return;
        if (payoutId == null) {
            int attempts = ((Number) row[6]).intValue();
            Instant created = instant(row[7]);
            if (created != null && created.plusSeconds(45).isAfter(Instant.now())) return;
            if (attempts >= 12) {
                tx.executeWithoutResult(st -> em.createNativeQuery(
                        "UPDATE wallet_withdrawals SET status = 'REVIEW', failure_reason = :r, updated_at = now() "
                                + "WHERE id = :id AND status = 'PENDING'")
                        .setParameter("r", "The payment service did not confirm this withdrawal after several tries. Please contact support before trying again.")
                        .setParameter("id", id).executeUpdate());
                return;
            }
            submit(id);
            return;
        }
        try {
            PayoutResult p = monimeClient.getPayout(payoutId);
            if (p != null) applyPayoutStatus(id, p);
        } catch (RuntimeException e) {
            log.warn("Wallet: could not read payout {} for withdrawal {}: {}", payoutId, id, e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    List<UUID> openWithdrawalIds() {
        List<Object> rows = em.createNativeQuery(
                "SELECT id FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW') ORDER BY created_at LIMIT 50").getResultList();
        List<UUID> out = new ArrayList<>();
        for (Object r : rows) out.add((UUID) r);
        return out;
    }

    private void applyPayoutStatus(UUID id, PayoutResult p) {
        String s = p.status() == null ? "" : p.status();
        if ("completed".equalsIgnoreCase(s)) {
            Integer changed = tx.execute(st -> em.createNativeQuery(
                    "UPDATE wallet_withdrawals SET status = 'COMPLETED', completed_at = now(), updated_at = now(), failure_reason = NULL "
                            + "WHERE id = :id AND status IN ('PENDING', 'REVIEW')").setParameter("id", id).executeUpdate());
            if (changed != null && changed > 0) notifyWithdrawal(id, "completed");
        } else if ("failed".equalsIgnoreCase(s) || "rejected".equalsIgnoreCase(s) || "canceled".equalsIgnoreCase(s)) {
            String reason = p.failureDetail() != null && p.failureDetail().message() != null
                    ? p.failureDetail().message() : "The payment provider could not complete this withdrawal.";
            failAndReverse(id, reason.length() > 450 ? reason.substring(0, 450) : reason);
        }
    }

    private void recordPayout(UUID id, String payoutId) {
        tx.executeWithoutResult(st -> em.createNativeQuery(
                "UPDATE wallet_withdrawals SET monime_payout_id = :p, updated_at = now() WHERE id = :id AND monime_payout_id IS NULL")
                .setParameter("p", payoutId).setParameter("id", id).executeUpdate());
    }

    /** Marks a withdrawal FAILED and gives an admin's money back. Safe to run twice. */
    private void failAndReverse(UUID id, String reason) {
        final AtomicBoolean changed = new AtomicBoolean(false);
        tx.executeWithoutResult(st -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT status, scope, owner_user_id, amount_value FROM wallet_withdrawals WHERE id = :id FOR UPDATE")
                    .setParameter("id", id).getResultList();
            if (rows.isEmpty()) return;
            Object[] r = rows.get(0);
            String status = (String) r[0];
            if (!"PENDING".equals(status) && !"REVIEW".equals(status)) return;
            em.createNativeQuery("UPDATE wallet_withdrawals SET status = 'FAILED', failure_reason = :r, updated_at = now() WHERE id = :id")
                    .setParameter("r", reason).setParameter("id", id).executeUpdate();
            changed.set(true);
            if ("ADMIN".equals(r[1])) {
                UUID owner = (UUID) r[2];
                long amount = ((Number) r[3]).longValue();
                ensureWallet(owner);
                lockedBalance(owner);
                long after = credit(owner, amount);
                insertEntry(owner, "WITHDRAWAL_REVERSAL", amount, after, "Withdrawal failed - money returned", null, null, id, null);
            }
        });
        if (changed.get()) notifyWithdrawal(id, "failed");
    }

    /** Emails the owner of a withdrawal (admin or super admin) at their registered address. Never throws. */
    @SuppressWarnings("unchecked")
    private void notifyWithdrawal(UUID id, String stage) {
        try {
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT w.owner_user_id, w.amount_value, w.fee_value, w.method, w.provider_id, w.destination, "
                                    + "w.holder_name, w.failure_reason, w.scope FROM wallet_withdrawals w WHERE w.id = :id")
                    .setParameter("id", id).getResultList();
            if (rows.isEmpty()) return;
            Object[] r = rows.get(0);
            User owner = users.findById((UUID) r[0]).orElse(null);
            if (owner == null) return;
            String dest = ("momo".equals(r[3]) ? MOMO_PROVIDERS.getOrDefault((String) r[4], "Mobile money") : "Bank")
                    + " " + r[5] + (r[6] == null ? "" : " (" + r[6] + ")");
            emails.sendWalletWithdrawalEmail(owner, stage, money(((Number) r[1]).longValue()),
                    ((Number) r[2]).longValue() == 0 ? "Free" : money(((Number) r[2]).longValue()), dest, (String) r[7], refOf(id));
        } catch (RuntimeException e) {
            log.warn("Wallet: could not queue the withdrawal email for {}: {}", id, e.toString());
        }
    }

    private Object[] loadWithdrawal(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT status, monime_payout_id, amount_value, method, provider_id, destination, attempts, created_at "
                                + "FROM wallet_withdrawals WHERE id = :id")
                .setParameter("id", id).getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------ destination checks

    private record Dest(String method, String providerId, String destination, String holder) {
        String label() {
            return ("momo".equals(method) ? MOMO_PROVIDERS.getOrDefault(providerId, "Mobile money") : "Bank") + " " + destination;
        }
    }

    private Dest resolveDestination(User me, String method, String providerId, String destination) {
        String m = method == null ? "" : method.trim().toLowerCase();
        String p = providerId == null ? "" : providerId.trim();
        String d = destination == null ? "" : destination.trim();
        if ("momo".equals(m)) {
            if (!MOMO_PROVIDERS.containsKey(p)) throw new IllegalArgumentException("Choose Orange Money or Africell.");
            if (!d.matches("^\\+232\\d{8}$")) throw new IllegalArgumentException("Phone number must look like +23277123456.");
            String holder = null;
            try {
                ProviderKycResult kyc = monimeClient.getProviderKyc(p, d);
                if (kyc != null && kyc.account() != null) {
                    holder = kyc.account().holderName() != null ? kyc.account().holderName() : kyc.account().name();
                }
            } catch (RuntimeException e) {
                log.warn("Wallet: name lookup failed for {} {}: {}", p, d, e.toString());
            }
            return new Dest("momo", p, d, holder);
        }
        if ("bank".equals(m)) {
            if (p.isEmpty()) throw new IllegalArgumentException("Choose a bank.");
            String account = BankTransferService.normalizeAccountNumber(d);
            if (account == null || account.isBlank()) throw new IllegalArgumentException("Enter the bank account number.");
            BankAccountVerificationResponse v = banks.verifyAccount(me.getId(), p, account);
            if (v == null || !v.verified()) {
                throw new IllegalArgumentException(v != null && v.message() != null ? v.message() : "The bank account could not be verified.");
            }
            return new Dest("bank", p, account, v.holderName());
        }
        throw new IllegalArgumentException("Choose mobile money or bank.");
    }

    // ------------------------------------------------------------------ PIN, roles, money helpers

    private void verifyPin(User me, String pin) {
        if (pin == null || !pin.matches("^\\d{4}$")) throw new IllegalArgumentException("Enter your 4-digit PIN.");
        User fresh = users.findById(me.getId()).orElseThrow(() -> new IllegalArgumentException("Admin account not found."));
        if (fresh.getPinHash() == null || fresh.getPinHash().isEmpty()) {
            throw new IllegalArgumentException("Set your admin PIN first (Menu > Change PIN).");
        }
        if (passwordEncoder.matches(pin, fresh.getPinHash())) {
            users.clearFailedPinAttempts(me.getId());
            return;
        }
        users.recordFailedPinAttempt(me.getId(), Instant.now());
        User state = users.findById(me.getId()).orElse(fresh);
        if (state.getPinAttempts() >= 2) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PIN is disabled. Please log in again.");
        }
        throw new IllegalArgumentException("Wrong PIN. Please try again.");
    }

    static boolean isSuper(User u) {
        return u != null && "SUPER_ADMIN".equals(u.getRole());
    }

    private void requireSuper(User u) {
        if (!isSuper(u)) throw new IllegalArgumentException("Only a super admin can do this.");
    }

    static long toMinor(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("Enter an amount.");
        BigDecimal scaled;
        try {
            scaled = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The amount can have at most 2 decimal places.");
        }
        long minor = scaled.movePointRight(2).longValueExact();
        if (minor < MIN_MINOR) throw new IllegalArgumentException("The minimum amount is " + money(MIN_MINOR) + ".");
        if (minor > MAX_MINOR) throw new IllegalArgumentException("That amount is too large.");
        return minor;
    }

    static String money(long minor) {
        return CURRENCY + " " + BigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    // ------------------------------------------------------------------ small SQL helpers

    private void lockPlatform() {
        // CAST: the function returns "void", which Hibernate cannot read as a result column.
        em.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:k) AS text)").setParameter("k", ADVISORY_LOCK).getSingleResult();
    }

    private void ensureWallet(UUID userId) {
        em.createNativeQuery("INSERT INTO admin_wallets (user_id, balance_value) VALUES (:u, 0) ON CONFLICT (user_id) DO NOTHING")
                .setParameter("u", userId).executeUpdate();
    }

    private long lockedBalance(UUID userId) {
        return ((Number) em.createNativeQuery("SELECT balance_value FROM admin_wallets WHERE user_id = :u FOR UPDATE")
                .setParameter("u", userId).getSingleResult()).longValue();
    }

    /** Adds money to an admin wallet (creating it if needed) and returns the new balance. Must run in a transaction. */
    private long credit(UUID userId, long minor) {
        ensureWallet(userId);
        long balance = lockedBalance(userId);
        long after = balance + minor;
        em.createNativeQuery("UPDATE admin_wallets SET balance_value = :b, updated_at = now() WHERE user_id = :u")
                .setParameter("b", after).setParameter("u", userId).executeUpdate();
        return after;
    }

    private void insertEntry(UUID userId, String kind, long amount, long balanceAfter, String note,
                             UUID counterpartyId, String counterpartyName, UUID withdrawalId, UUID createdBy) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", UUID.randomUUID());
        e.put("user_id", userId);
        e.put("kind", kind);
        e.put("amount_value", amount);
        e.put("balance_after", balanceAfter);
        e.put("note", note);
        e.put("counterparty_id", counterpartyId);
        e.put("counterparty_name", counterpartyName);
        e.put("withdrawal_id", withdrawalId);
        e.put("created_by", createdBy);
        insertRow("wallet_entries", e);
    }

    /**
     * INSERT of only the non-null columns. (A null bound without a type is sent to PostgreSQL as "bytea" by
     * Hibernate, which fails for uuid / text columns; leaving the column out gives the same row.)
     * Column names are fixed strings from this class, never user input.
     */
    private void insertRow(String table, Map<String, Object> columns) {
        StringBuilder names = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> c : columns.entrySet()) {
            if (c.getValue() == null) continue;
            if (names.length() > 0) { names.append(", "); marks.append(", "); }
            names.append(c.getKey());
            marks.append(":p").append(values.size());
            values.add(c.getValue());
        }
        var q = em.createNativeQuery("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")");
        for (int i = 0; i < values.size(); i++) q.setParameter("p" + i, values.get(i));
        q.executeUpdate();
    }

    private long scalar(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        if (params.length > 0) q.setParameter("u", params[0]);
        return ((Number) q.getSingleResult()).longValue();
    }

    private long scalarTx(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.time.ZonedDateTime zdt) return zdt.toInstant();
        if (o instanceof java.util.Date dt) return dt.toInstant();
        return null;
    }
}
