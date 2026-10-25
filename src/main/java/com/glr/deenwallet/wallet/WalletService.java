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

    public WalletService(TransactionTemplate tx, WalletMonimeApi monimeApi, MonimeClient monimeClient,
                         com.glr.deenwallet.monime.MonimeProperties monimeProps, BankTransferService banks,
                         UserRepository users, PasswordEncoder passwordEncoder) {
        this.tx = tx;
        this.monimeApi = monimeApi;
        this.monimeClient = monimeClient;
        this.monimeProps = monimeProps;
        this.banks = banks;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------ views

    public record Summary(String role, boolean superAdmin, boolean configured, String error,
                          Long monimeAvailable, long heldForAdmins, long pendingOut, Long withdrawable,
                          long myBalance, long myPending, String currency) { }

    public record AdminRow(UUID id, String name, String email, long balance) { }

    public record Activity(String kind, String id, Instant at, long amount, String status, String ownerName,
                           String detail, String reason) { }

    public record TxRow(String id, String type, long amount, String timestamp, String reference, Long balanceAfter,
                        String label, String detail, String dwCode, String customer, String status) { }

    public record TxPage(List<TxRow> rows, String next, String error) { }

    public record Options(List<Map<String, String>> momo, List<Map<String, String>> banks) { }

    public record Lookup(boolean ok, String holderName, String message) { }

    // ------------------------------------------------------------------ summary

    public Summary summary(User me) {
        boolean isSuper = isSuper(me);
        boolean configured = monimeApi.configured();
        String error = null;
        Long monime = null;
        if (isSuper) {
            if (!configured) {
                error = "The Monime financial account is not set up on the server.";
            } else {
                try {
                    monime = monimeApi.availableBalance(false);
                } catch (IllegalStateException e) {
                    error = e.getMessage();
                }
            }
        }
        long held = scalar("SELECT COALESCE(SUM(balance_value), 0) FROM admin_wallets");
        long pending = scalar("SELECT COALESCE(SUM(amount_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
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
        JsonNode root;
        try {
            root = monimeApi.transactions(type, after, 20);
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
            }
        }
        Map<String, Match> matches = match(ids);
        List<TxRow> rows = new ArrayList<>();
        for (JsonNode n : items) {
            String kind = n.path("type").asText("");
            long amount = n.path("amount").path("value").asLong(0);
            JsonNode after1 = n.path("financialAccount").path("balance").path("after").path("value");
            Long balanceAfter = after1.isNumber() ? after1.asLong() : null;
            String feeCode = text(n.path("originatingFee").path("code"));
            String reversal = text(n.path("originatingReversal").path("originTxnId"));
            if (reversal == null) reversal = text(n.path("originatingReversal").path("originTxnRef"));
            Set<String> owners = new HashSet<>();
            collectOwnerIds(n.path("ownershipGraph").path("owner"), owners, 0);
            String ownerType = firstOwnerType(n.path("ownershipGraph").path("owner"));
            Match m = null;
            for (String o : owners) {
                if (matches.containsKey(o)) { m = matches.get(o); break; }
            }
            String label;
            String detail = null;
            String dwCode = null;
            String customer = null;
            String status = null;
            if (feeCode != null) {
                label = "Monime fee";
                detail = feeCode;
            } else if (reversal != null) {
                label = "Reversal";
            } else if (m != null && m.withdrawalOwner != null) {
                label = "Withdrawal";
                detail = m.withdrawalOwner;
                status = m.withdrawalStatus;
            } else if (m != null) {
                label = "credit".equals(kind) ? "Customer payment" : (m.refund ? "Refund to customer" : "Customer payout");
                dwCode = m.code;
                customer = m.customer;
                status = m.status;
                detail = m.service;
            } else if (ownerType != null && ownerType.contains("internal_transfer")) {
                label = "Internal transfer";
            } else {
                label = "credit".equals(kind) ? "Payment received" : "Money sent out";
                if (ownerType != null) detail = ownerType.replace('_', ' ');
            }
            rows.add(new TxRow(n.path("id").asText(""), kind, amount, n.path("timestamp").asText(null),
                    n.path("reference").asText(null), balanceAfter, label, detail, dwCode, customer, status));
        }
        String next = text(root.path("pagination").path("next"));
        return new TxPage(rows, next, null);
    }

    private record Match(String code, String customer, String service, String status, boolean refund,
                         String withdrawalOwner, String withdrawalStatus) { }

    @SuppressWarnings("unchecked")
    private Map<String, Match> match(Set<String> ids) {
        Map<String, Match> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        List<Object[]> t = em.createNativeQuery(
                        "SELECT t.monime_payment_code_id, t.monime_payout_id, t.refund_payout_id, t.transaction_code, "
                                + "u.full_name, t.service_type, t.status FROM transactions t LEFT JOIN users u ON u.id = t.user_id "
                                + "WHERE t.monime_payment_code_id IN (:ids) OR t.monime_payout_id IN (:ids) OR t.refund_payout_id IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : t) {
            String pay = (String) r[0];
            String payout = (String) r[1];
            String refund = (String) r[2];
            String code = (String) r[3];
            String name = (String) r[4];
            String service = r[5] == null ? null : String.valueOf(r[5]).replace('_', ' ').toLowerCase();
            String status = r[6] == null ? null : String.valueOf(r[6]);
            if (pay != null) out.putIfAbsent(pay, new Match(code, name, service, status, false, null, null));
            if (payout != null) out.putIfAbsent(payout, new Match(code, name, service, status, false, null, null));
            if (refund != null) out.putIfAbsent(refund, new Match(code, name, service, status, true, null, null));
        }
        List<Object[]> w = em.createNativeQuery(
                        "SELECT w.monime_payout_id, u.full_name, w.status FROM wallet_withdrawals w "
                                + "LEFT JOIN users u ON u.id = w.owner_user_id WHERE w.monime_payout_id IN (:ids)")
                .setParameter("ids", ids).getResultList();
        for (Object[] r : w) {
            out.put((String) r[0], new Match(null, null, null, null, false,
                    r[1] == null ? "Admin" : (String) r[1], (String) r[2]));
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
        return new Options(momo, bankList);
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

        tx.executeWithoutResult(st -> {
            lockPlatform();
            long held = scalarTx("SELECT COALESCE(SUM(balance_value), 0) FROM admin_wallets");
            long pending = scalarTx("SELECT COALESCE(SUM(amount_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
            long free = monime - held - pending;
            if (minor > free) {
                throw new IllegalArgumentException("Not enough free money in the Monime account. Available to send: "
                        + money(Math.max(0, free)) + ".");
            }
            long after = credit(target.getId(), minor);
            insertEntry(target.getId(), "TRANSFER_IN", minor, after, finalNote, me.getId(), me.getFullName(), null, me.getId());
        });
        log.info("Wallet: {} transferred {} to admin {}", me.getEmail(), money(minor), target.getEmail());
        return summary(me);
    }

    // ------------------------------------------------------------------ withdraw (both roles)

    public Summary withdraw(User me, String method, String providerId, String destination, BigDecimal amount, String pin) {
        if (!isSuper(me) && !"ADMIN".equals(me.getRole())) throw new IllegalArgumentException("Only admins can use the wallet.");
        verifyPin(me, pin);
        long minor = toMinor(amount);
        Dest d = resolveDestination(me, method, providerId, destination);
        if (!monimeApi.configured()) throw new IllegalStateException("The Monime financial account is not set up on the server.");
        long monime = monimeApi.availableBalance(true);
        boolean isSuper = isSuper(me);
        UUID id = UUID.randomUUID();

        tx.executeWithoutResult(st -> {
            if (isSuper) {
                lockPlatform();
                long held = scalarTx("SELECT COALESCE(SUM(balance_value), 0) FROM admin_wallets");
                long pending = scalarTx("SELECT COALESCE(SUM(amount_value), 0) FROM wallet_withdrawals WHERE status IN ('PENDING', 'REVIEW')");
                long free = monime - held - pending;
                if (minor > free) {
                    throw new IllegalArgumentException("Not enough free money to withdraw. Available: " + money(Math.max(0, free))
                            + " (the rest is held for admin wallets or already on its way out).");
                }
            } else {
                ensureWallet(me.getId());
                long balance = lockedBalance(me.getId());
                if (minor > balance) {
                    throw new IllegalArgumentException("Your wallet balance is " + money(balance) + ".");
                }
                if (minor > monime) {
                    throw new IllegalStateException("The payment account is short right now. Please contact the super admin.");
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
            w.put("method", d.method);
            w.put("provider_id", d.providerId);
            w.put("destination", d.destination);
            w.put("holder_name", d.holder == null || d.holder.isBlank() ? null : (d.holder.length() > 150 ? d.holder.substring(0, 150) : d.holder));
            insertRow("wallet_withdrawals", w);
        });
        log.info("Wallet: withdrawal {} of {} started by {}", id, money(minor), me.getEmail());
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
                        .setParameter("r", "Monime did not confirm this withdrawal after several tries. Check the Monime dashboard before trying again.")
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
            tx.executeWithoutResult(st -> em.createNativeQuery(
                    "UPDATE wallet_withdrawals SET status = 'COMPLETED', completed_at = now(), updated_at = now(), failure_reason = NULL "
                            + "WHERE id = :id AND status IN ('PENDING', 'REVIEW')").setParameter("id", id).executeUpdate());
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
            if ("ADMIN".equals(r[1])) {
                UUID owner = (UUID) r[2];
                long amount = ((Number) r[3]).longValue();
                ensureWallet(owner);
                lockedBalance(owner);
                long after = credit(owner, amount);
                insertEntry(owner, "WITHDRAWAL_REVERSAL", amount, after, "Withdrawal failed - money returned", null, null, id, null);
            }
        });
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
