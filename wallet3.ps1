# DeenWallet Transfer - Admin Wallet update 3
#
# Run from the project root (the folder that contains pom.xml):
#     powershell -ExecutionPolicy Bypass -File .\wallet3.ps1
#
# What it does:
#   - Admin menu: "Admin management" and your email in the menu no longer disappear after closing and reopening the app.
#   - Wallet screen and emails no longer say "Monime" (except the "Monime reference" line you asked to keep).
#   - Payments and Payouts show the DeenWallet reference; "Monime ID" is removed. Search by DeenWallet reference,
#     payment reference, name, phone or email.
#   - Eye button hides / shows every amount on the wallet screen (super admin and admin), remembered on the device.
# Replaces 4 wallet Java files and patches admin.html in small parts. Everything is checked first; nothing is written
# unless all of it fits. No backup files are created.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run this from the folder that contains pom.xml.' }
$dir = 'src\main\java\com\glr\deenwallet\wallet\'
$htmlRel = 'src\main\resources\static\admin.html'
$svcPath = Join-Path $root ($dir + 'WalletService.java')
if (-not (Test-Path $svcPath) -or -not (Test-Path (Join-Path $root $htmlRel))) { throw 'Install wallet.ps1 and wallet2.ps1 first (the wallet files were not found).' }
$svcOld = [System.IO.File]::ReadAllText($svcPath, $utf8)
if ($svcOld.Contains('rowMatches(')) {
  Write-Host 'Wallet update 3 is already installed - nothing to do.' -ForegroundColor Yellow
  exit 0
}
if (-not $svcOld.Contains('feeOf(')) { throw 'Run wallet2.ps1 first. Nothing changed.' }
foreach ($n in @('WalletMailer', 'WalletMonimeApi', 'WalletController')) {
  if (-not (Test-Path (Join-Path $root ($dir + $n + '.java')))) { throw "$n.java was not found. Nothing changed." }
}

function Decode([string]$s) {
  return [regex]::Replace($s, '\{\{U([0-9A-F]{4})\}\}', { param($m) [string][char][Convert]::ToInt32($m.Groups[1].Value, 16) })
}
$newFiles = @()
function Replace-File([string]$rel, [string]$content) {
  $script:newFiles += ,@($rel, ((Decode $content).Replace("`r`n", "`n").TrimEnd("`n") + "`n"))
}
$html = [System.IO.File]::ReadAllText((Join-Path $root $htmlRel), $utf8)
$htmlCrlf = $html.Contains("`r`n")
$script:t = $html.Replace("`r`n", "`n")
function Edit([string]$old, [string]$new, [string]$what) {
  $old = (Decode $old).Replace("`r`n", "`n").TrimEnd("`n")
  $new = (Decode $new).Replace("`r`n", "`n").TrimEnd("`n")
  $i = $script:t.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0) { throw "admin.html: the '$what' part was not found (the file differs from the expected version). Nothing changed." }
  if ($script:t.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) { throw "admin.html: the '$what' part was found twice. Nothing changed." }
  $script:t = $script:t.Substring(0, $i) + $new + $script:t.Substring($i + $old.Length)
}

$f0 = @'
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
            Long fee = d.foldedFee > 0 ? Long.valueOf(d.foldedFee) : d.fee;
            List<Detail> details = new ArrayList<>(d.details);
            if (fee != null && !d.isFee) {
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

    private static final class Draft {
        String id, kind, ts, ref, label, detail, dwCode, customer, status, topOwner;
        long amount;
        Long balanceAfter;
        Long fee;
        long foldedFee;
        boolean isFee, folded;
        List<Detail> details;
    }

    private record Match(String code, String customer, String service, String status, boolean refund,
                         String withdrawalOwner, String withdrawalStatus, List<Detail> base,
                         Long depositFee, Long payoutFee, Long withdrawalFee) { }

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
            Match m = new Match(code, name, service, status, false, null, null, base, minor(r[14]), minor(r[15]), null);
            Match mRefund = new Match(code, name, service, status, true, null, null, base, minor(r[14]), minor(r[15]), null);
            if (pay != null) out.putIfAbsent(pay, m);
            if (payout != null) out.putIfAbsent(payout, m);
            if (refund != null) out.putIfAbsent(refund, mRefund);
            if (r[25] != null) out.putIfAbsent(String.valueOf(r[25]).toLowerCase(), m);
        }
        List<Object[]> w = em.createNativeQuery(
                        "SELECT w.monime_payout_id, u.full_name, w.status, w.method, w.provider_id, w.destination, w.holder_name, "
                                + "w.amount_value, w.fee_value, w.created_at, w.failure_reason, u.email, w.id FROM wallet_withdrawals w "
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
                    minor(r[8])));
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
        final long fee = feeOf(minor);

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
                ensureWallet(me.getId());
                long balance = lockedBalance(me.getId());
                if (minor > balance) {
                    throw new IllegalArgumentException("Your wallet balance is " + money(balance) + ".");
                }
                if (minor + fee > monime) {
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
                    money(((Number) r[2]).longValue()), dest, (String) r[7], refOf(id));
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
'@
Replace-File ($dir + 'WalletService.java') $f0

$f1 = @'
package com.glr.deenwallet.wallet;

import com.glr.deenwallet.user.User;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Emails for the admin wallet: withdrawal notices and transfer notices.
 * Self-contained (own look and sending) so it does not depend on the shared email classes.
 * Mail problems are logged and never break a withdrawal or a transfer.
 */
@Component
public class WalletMailer {

    private static final Logger log = LoggerFactory.getLogger(WalletMailer.class);
    private static final String BRAND = "#0F766E";
    private static final String TEXT = "#1F2937";
    private static final String MUTED = "#6B7280";
    private static final String BORDER = "#E5E7EB";

    @Value("${app.mail.from-email}")
    private String senderEmail;

    @Value("${app.mail.from-name}")
    private String senderName;

    @Value("${app.mail.support-email:support@deenwallapp.com}")
    private String supportEmail;

    private final JavaMailSender mailSender;

    public WalletMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Withdrawal notice (stage: started, completed or failed) to the owner's registered email. */
    @Async("emailTaskExecutor")
    public void sendWalletWithdrawalEmail(User admin, String stage, String amount, String fee,
                                          String destination, String failureReason, String reference) {
        boolean done = "completed".equals(stage);
        boolean failed = "failed".equals(stage);
        String title = done ? "Withdrawal completed" : (failed ? "Withdrawal failed" : "Withdrawal requested");
        String intro = done
                ? "Your withdrawal has been paid out."
                : (failed ? "Your withdrawal could not be completed. If it was taken from your admin wallet, the money has been returned to it."
                          : "We received your withdrawal request and it is being processed.");
        StringBuilder rows = new StringBuilder();
        rows.append(row("Reference", reference));
        rows.append(row("Amount", amount));
        rows.append(row("Service fee", fee));
        rows.append(row("Sent to", destination));
        if (failed && failureReason != null && !failureReason.isBlank()) rows.append(row("Reason", failureReason));
        String body = "<h2 style=\"margin:0 0 14px 0;color:" + BRAND + ";font-size:22px;text-align:center;\">" + esc(title) + "</h2>"
                + "<p style=\"margin:0 0 16px 0;color:" + MUTED + ";font-size:15px;line-height:1.6;\">Hello " + esc(admin.getFirstName()) + ", " + esc(intro) + "</p>"
                + table(rows)
                + "<p style=\"margin:0;color:" + MUTED + ";font-size:13px;text-align:center;\">If you did not make this withdrawal, contact support immediately.</p>";
        send(admin.getEmail(), title + " " + reference, wrap(title, body));
    }

    /** Transfer notice: to the receiving admin (received = true) and a receipt to the super admin. */
    @Async("emailTaskExecutor")
    public void sendWalletTransferEmail(User to, boolean received, String otherName, String amount,
                                        String note, String newBalance) {
        String title = received ? "Money added to your wallet" : "Transfer sent";
        String intro = received
                ? otherName + " sent money to your admin wallet. Internal transfers are free."
                : "You sent money to " + otherName + "'s admin wallet. Internal transfers are free.";
        StringBuilder rows = new StringBuilder();
        rows.append(row("Amount", amount));
        rows.append(row(received ? "From" : "To", otherName));
        if (note != null && !note.isBlank()) rows.append(row("Note", note));
        if (received && newBalance != null) rows.append(row("Your wallet balance", newBalance));
        String body = "<h2 style=\"margin:0 0 14px 0;color:" + BRAND + ";font-size:22px;text-align:center;\">" + esc(title) + "</h2>"
                + "<p style=\"margin:0 0 16px 0;color:" + MUTED + ";font-size:15px;line-height:1.6;\">Hello " + esc(to.getFirstName()) + ", " + esc(intro) + "</p>"
                + table(rows)
                + "<p style=\"margin:0;color:" + MUTED + ";font-size:13px;text-align:center;\">If you do not recognise this transfer, contact support immediately.</p>";
        send(to.getEmail(), received ? "You received " + amount + " in your wallet" : "Transfer sent " + amount, wrap(title, body));
    }

    private static String table(StringBuilder rows) {
        return "<div style=\"border:1px solid " + BORDER + ";border-radius:12px;overflow:hidden;margin-bottom:24px;\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;\">" + rows + "</table></div>";
    }

    private static String row(String label, String value) {
        return "<tr style=\"border-bottom:1px solid " + BORDER + ";\">"
                + "<td style=\"padding:14px 16px;font-size:14px;color:" + MUTED + ";\">" + esc(label) + "</td>"
                + "<td style=\"padding:14px 16px;font-size:14px;font-weight:600;color:" + TEXT + ";text-align:right;\">" + esc(value) + "</td></tr>";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String wrap(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>" + esc(title) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#F3F4F6;font-family:'Helvetica Neue',Helvetica,Arial,sans-serif;\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;background:#F3F4F6;padding:32px 16px;\"><tr><td align=\"center\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:540px;width:100%;background:#FFFFFF;border-radius:16px;overflow:hidden;border:1px solid " + BORDER + ";\">"
                + "<tr><td style=\"background:" + BRAND + ";padding:22px 24px;text-align:center;font-size:22px;font-weight:800;letter-spacing:1px;color:#FFFFFF;\">DEENWALLET TRANSFER</td></tr>"
                + "<tr><td style=\"padding:32px 24px;background:#FFFFFF;\">" + body + "</td></tr>"
                + "<tr><td style=\"padding:20px 24px;background:#F9FAFB;border-top:1px solid " + BORDER + ";text-align:center;color:" + MUTED + ";font-size:11px;\">"
                + "DEEN WALLET LTD &middot; Need help? support@deenwallapp.com<br>This is an automated message, please do not reply.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private void send(String toEmail, String subject, String html) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Wallet email '{}' skipped: no recipient address", subject);
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(senderEmail, senderName);
                helper.setReplyTo(supportEmail, "DeenWallet Transfer Support");
                helper.setTo(toEmail);
                helper.setSubject(subject);
                helper.setText(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(), html);
                mailSender.send(message);
                log.info("Wallet email sent to {} - {}", toEmail, subject);
                return;
            } catch (Exception e) {
                log.warn("Wallet email to {} failed (attempt {}/3): {}", toEmail, attempt, e.toString());
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
'@
Replace-File ($dir + 'WalletMailer.java') $f1

$f2 = @'
package com.glr.deenwallet.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glr.deenwallet.monime.MonimeProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live, read-only look at the Monime financial account that DeenWallet uses
 * (the same account the customer payouts are paid from).
 *
 * Monime API (caph 2025-08-23):
 *   GET /v1/financial-accounts/{id}?withBalance=true     -> result.balance.available.value (minor units)
 *   GET /v1/financial-transactions?financialAccountId=..&type=credit|debit&limit=..&after=..
 * Answers are cached for a few seconds so several admins watching the screen do not hammer Monime.
 */
@Component
public class WalletMonimeApi {

    private static final Duration TTL = Duration.ofSeconds(5);
    private static final int MAX_CACHE = 200;

    private final RestClient monime;
    private final MonimeProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Instant at, String body) { }

    public WalletMonimeApi(RestClient monimeRestClient, MonimeProperties props) {
        this.monime = monimeRestClient;
        this.props = props;
    }

    public boolean configured() {
        String id = props.defaultFinancialAccountId();
        return id != null && !id.isBlank();
    }

    public String accountId() {
        return props.defaultFinancialAccountId();
    }

    /** Available balance in minor units. Throws IllegalStateException with a readable message when Monime cannot answer. */
    public long availableBalance(boolean fresh) {
        requireConfigured();
        String body = get("balance", fresh, () -> monime.get()
                .uri(b -> b.path("/v1/financial-accounts/{id}").queryParam("withBalance", true).build(accountId()))
                .retrieve().body(String.class));
        try {
            JsonNode value = mapper.readTree(body).path("result").path("balance").path("available").path("value");
            if (!value.isNumber()) throw new IllegalStateException("The balance could not be read.");
            return value.asLong();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("The balance could not be read.");
        }
    }

    /** One page of the account's financial transactions. type is "credit", "debit" or null for both. */
    public JsonNode transactions(String type, String after, int limit) {
        requireConfigured();
        String t = "credit".equals(type) || "debit".equals(type) ? type : null;
        int size = Math.max(1, Math.min(limit, 50));
        String key = "tx|" + t + "|" + (after == null ? "" : after) + "|" + size;
        String body = get(key, false, () -> monime.get()
                .uri(b -> {
                    b.path("/v1/financial-transactions")
                            .queryParam("financialAccountId", accountId())
                            .queryParam("limit", size);
                    if (t != null) b.queryParam("type", t);
                    if (after != null && !after.isBlank()) b.queryParam("after", after);
                    return b.build();
                })
                .retrieve().body(String.class));
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("The transaction list could not be read.");
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("The payment account is not set up on the server.");
        }
    }

    private String get(String key, boolean fresh, java.util.function.Supplier<String> call) {
        Cached c = cache.get(key);
        if (!fresh && c != null && c.at().plus(TTL).isAfter(Instant.now())) return c.body();
        try {
            String body = call.get();
            if (body == null || body.isBlank()) throw new IllegalStateException("The payment service returned an empty answer.");
            if (cache.size() > MAX_CACHE) cache.clear();
            cache.put(key, new Cached(Instant.now(), body));
            return body;
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("The payment service could not be reached right now. Try again in a moment.");
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("The payment service refused the request (HTTP " + e.getStatusCode().value() + ").");
        }
    }
}
'@
Replace-File ($dir + 'WalletMonimeApi.java') $f2

$f3 = @'
package com.glr.deenwallet.wallet;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Admin Wallet screen. /api/admin/** is already limited to ADMIN / SUPER_ADMIN by SecurityConfig;
 * the super-admin-only parts are checked again here.
 * Every rule violation answers 400 (never 401/403, which the admin app treats as "session expired").
 */
@RestController
@RequestMapping("/api/admin/wallet")
public class WalletController {

    public record TransferRequest(UUID adminId, BigDecimal amount, String note, String pin) { }

    public record WithdrawRequest(String method, String providerId, String destination, BigDecimal amount, String pin) { }

    private final WalletService wallet;
    private final UserRepository users;

    public WalletController(WalletService wallet, UserRepository users) {
        this.wallet = wallet;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<WalletService.Summary> summary() {
        return ResponseEntity.ok(wallet.summary(admin()));
    }

    /** Live payments in / payouts out of the DeenWallet account. type = credit (payments in) or debit (payouts out). Super admin only. */
    @GetMapping("/transactions")
    public ResponseEntity<WalletService.TxPage> transactions(@RequestParam(required = false) String type,
                                                             @RequestParam(required = false) String after,
                                                             @RequestParam(required = false) String q) {
        requireSuper(admin());
        if (q != null && !q.isBlank()) return ResponseEntity.ok(wallet.search(type, q));
        return ResponseEntity.ok(wallet.transactions(type, after));
    }

    @GetMapping("/activity")
    public ResponseEntity<List<WalletService.Activity>> activity() {
        return ResponseEntity.ok(wallet.activity(admin()));
    }

    @GetMapping("/admins")
    public ResponseEntity<List<WalletService.AdminRow>> admins() {
        requireSuper(admin());
        return ResponseEntity.ok(wallet.admins());
    }

    @GetMapping("/options")
    public ResponseEntity<WalletService.Options> options() {
        admin();
        return ResponseEntity.ok(wallet.options());
    }

    @GetMapping("/lookup")
    public ResponseEntity<WalletService.Lookup> lookup(@RequestParam String method,
                                                       @RequestParam String providerId,
                                                       @RequestParam String destination) {
        return ResponseEntity.ok(wallet.lookup(admin(), method, providerId, destination));
    }

    @PostMapping("/transfer")
    public ResponseEntity<WalletService.Summary> transfer(@RequestBody TransferRequest r) {
        User me = admin();
        requireSuper(me);
        return ResponseEntity.ok(wallet.transfer(me, r.adminId(), r.amount(), r.note(), r.pin()));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<WalletService.Summary> withdraw(@RequestBody WithdrawRequest r) {
        return ResponseEntity.ok(wallet.withdraw(admin(), r.method(), r.providerId(), r.destination(), r.amount(), r.pin()));
    }

    private void requireSuper(User u) {
        if (!WalletService.isSuper(u)) throw new IllegalArgumentException("Only a super admin can do this.");
    }

    private User admin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = users.findById(UUID.fromString((String) a.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        if (!"ADMIN".equals(u.getRole()) && !"SUPER_ADMIN".equals(u.getRole()))
            throw new SecurityException("Access denied: admin only");
        if (!u.isActive()) throw new SecurityException("Admin account is inactive.");
        return u;
    }
}
'@
Replace-File ($dir + 'WalletController.java') $f3

$o0 = @'
        .wallet-detail-row strong {
            text-align: right;
            word-break: break-word;
        }
'@
$n0 = @'
        .wallet-detail-row strong {
            text-align: right;
            word-break: break-word;
        }

        .wallet-eye {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 28px;
            height: 28px;
            margin-left: 6px;
            padding: 0;
            border: none;
            border-radius: 50%;
            background: rgba(255, 255, 255, .16);
            color: #fff;
            cursor: pointer;
        }

        .wallet-eye svg {
            width: 16px;
            height: 16px;
            stroke: currentColor;
            fill: none;
            stroke-width: 2;
            stroke-linecap: round;
            stroke-linejoin: round;
        }

        .wallet-search-wrap {
            margin: 8px 0 4px;
        }
'@
Edit $o0 $n0 'css'

$o1 = @'
<div class="wallet-hero-top"><span id="wallet-hero-label">Wallet</span><span class="wallet-live"><i></i> Live</span></div>
'@
$n1 = @'
<div class="wallet-hero-top"><span style="display:inline-flex;align-items:center;"><span id="wallet-hero-label">Wallet</span><button type="button" class="wallet-eye" id="wallet-eye" aria-label="Hide or show amounts"></button></span><span class="wallet-live"><i></i> Live</span></div>
'@
Edit $o1 $n1 'hero top'

$o2 = @'
<p class="admin-card-sub" id="wallet-list-sub" style="margin:2px 0 4px;"></p>
'@
$n2 = @'
<p class="admin-card-sub" id="wallet-list-sub" style="margin:2px 0 4px;"></p>
                <div class="wallet-search-wrap hidden" id="wallet-search-wrap"><input id="wallet-search" class="wallet-input" type="search" autocomplete="off" placeholder="Search DeenWallet or payment reference" /></div>
'@
Edit $o2 $n2 'search box'

$o3 = @'
has no Monime fee.
'@
$n3 = @'
has no service fee.
'@
Edit $o3 $n3 'free text'

$o4 = @'
var walletState = { summary: null, tab: 'activity', rows: [], next: null, pages: 0, timer: null, loading: false, options: null, admins: [] };
'@
$n4 = @'
var walletState = { summary: null, tab: 'activity', rows: [], next: null, pages: 0, timer: null, loading: false, options: null, admins: [], seq: 0, query: '' };
'@
Edit $o4 $n4 'state'

$o5 = @'
            function wMoney(minor) { return 'SLE ' + wFmt(minor); }
'@
$n5 = @'
            function wMoney(minor) { return 'SLE ' + wFmt(minor); }
            // The eye button hides every amount on the wallet screen (remembered on this device).
            var walletHidden = false;
            try { walletHidden = localStorage.getItem('deenwallet_wallet_hidden') === '1'; } catch (e) { walletHidden = false; }
            var W_MASK = '\u2022\u2022\u2022\u2022';
            var W_EYE = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>';
            var W_EYE_OFF = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 3l18 18"/><path d="M10.6 6.1A9.9 9.9 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.7C3.9 8.4 2 12 2 12s3.6 7 10 7c1.7 0 3.2-.4 4.5-1"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/></svg>';
            function wAmt(minor) { return walletHidden ? W_MASK : wFmt(minor); }
            function wMoneyH(minor) { return walletHidden ? 'SLE ' + W_MASK : wMoney(minor); }
            function walletPaintEye() {
                var b = document.getElementById('wallet-eye');
                if (!b) return;
                b.innerHTML = walletHidden ? W_EYE_OFF : W_EYE;
                b.setAttribute('aria-label', walletHidden ? 'Show amounts' : 'Hide amounts');
            }
'@
Edit $o5 $n5 'mask helpers'

$o6 = @'
                    label.textContent = 'Monime account balance';
                    amount.textContent = s.monimeAvailable == null ? 'Unavailable' : wMoney(s.monimeAvailable);
                    sub.textContent = s.monimeAvailable == null
                        ? (s.error || 'Could not read the Monime balance.')
                        : 'Free to withdraw: ' + wMoney(s.withdrawable) + '  |  Held for admin wallets: ' + wMoney(s.heldForAdmins)
                            + (s.pendingOut > 0 ? '  |  On its way out: ' + wMoney(s.pendingOut) : '');
                } else {
                    label.textContent = 'My wallet';
                    amount.textContent = wMoney(s.myBalance);
                    sub.textContent = s.myPending > 0 ? 'Withdrawals in progress: ' + wMoney(s.myPending) : 'Your pay from the super admin appears here. You can withdraw it.';
'@
$n6 = @'
                    label.textContent = 'DeenWallet balance';
                    amount.textContent = s.monimeAvailable == null ? 'Unavailable' : wMoneyH(s.monimeAvailable);
                    sub.textContent = s.monimeAvailable == null
                        ? (s.error || 'Could not read the balance.')
                        : 'Free to withdraw: ' + wMoneyH(s.withdrawable) + '  |  Held for admin wallets: ' + wMoneyH(s.heldForAdmins)
                            + (s.pendingOut > 0 ? '  |  On its way out: ' + wMoneyH(s.pendingOut) : '');
                } else {
                    label.textContent = 'My wallet';
                    amount.textContent = wMoneyH(s.myBalance);
                    sub.textContent = s.myPending > 0 ? 'Withdrawals in progress: ' + wMoneyH(s.myPending) : 'Your pay from the super admin appears here. You can withdraw it.';
'@
Edit $o6 $n6 'hero amounts'

$o7 = @'
(credit ? '+' : '-') + ' ' + wFmt(r.amount) + '</div>'
'@
$n7 = @'
(credit ? '+' : '-') + ' ' + wAmt(r.amount) + '</div>'
'@
Edit $o7 $n7 'amt a'

$o8 = @'
Fee ' + wFmt(r.fee)
'@
$n8 = @'
Fee ' + wAmt(r.fee)
'@
Edit $o8 $n8 'amt b'

$o9 = @'
Balance ' + wFmt(r.balanceAfter)
'@
$n9 = @'
Balance ' + wAmt(r.balanceAfter)
'@
Edit $o9 $n9 'amt c'

$o10 = @'
(out ? '-' : '+') + ' ' + wFmt(a.amount)
'@
$n10 = @'
(out ? '-' : '+') + ' ' + wAmt(a.amount)
'@
Edit $o10 $n10 'amt d'

$o11 = @'
wallet-amount">' + wMoney(a.balance)
'@
$n11 = @'
wallet-amount">' + wMoneyH(a.balance)
'@
Edit $o11 $n11 'amt e'

$o12 = @'
' &middot; Monime fee ' + escapeHtml(wMoney(r.fee)) + ' included below'
'@
$n12 = @'
' &middot; Service fee ' + escapeHtml(wMoney(r.fee)) + ' included below'
'@
Edit $o12 $n12 'detail fee text'

$o13 = @'
                sub.textContent = tab === 'payments' ? 'Every payment into the Monime account, live.'
                    : tab === 'payouts' ? 'Every payout out of the Monime account, live.'
                    : tab === 'admins' ?
'@
$n13 = @'
                var searching = (tab === 'payments' || tab === 'payouts') && walletState.query;
                document.getElementById('wallet-search-wrap').classList.toggle('hidden', !(tab === 'payments' || tab === 'payouts'));
                sub.textContent = searching ? 'Results for "' + walletState.query + '" in the latest 600 entries.'
                    : tab === 'payments' ? 'Every payment received by DeenWallet, live.'
                    : tab === 'payouts' ? 'Every payout sent by DeenWallet, live.'
                    : tab === 'admins' ?
'@
Edit $o13 $n13 'list texts'

$o14 = @'
list.innerHTML = '<div class="empty-state">' + (tab === 'admins' ? 'No admin accounts yet.' : 'Nothing here yet.') + '</div>';
'@
$n14 = @'
list.innerHTML = walletState.loading ? '' : '<div class="empty-state">' + (tab === 'admins' ? 'No admin accounts yet.' : (searching ? 'No match found.' : 'Nothing here yet.')) + '</div>';
'@
Edit $o14 $n14 'empty text'

$o15 = @'
            function walletLoadList(append) {
                var tab = walletState.tab;
                var loading = document.getElementById('wallet-loading');
                if (walletState.loading) return Promise.resolve();
                walletState.loading = true;
                if (!append && !walletState.rows.length) show(loading);
                var req;
                if (tab === 'payments' || tab === 'payouts') {
                    var params = { type: tab === 'payments' ? 'credit' : 'debit' };
                    if (append && walletState.next) params.after = walletState.next;
                    req = adminApiRequest('/api/admin/wallet/transactions', { params: params }).then(function (page) {
                        if (page.error) throw new Error(page.error);
                        walletState.rows = append ? walletState.rows.concat(page.rows || []) : (page.rows || []);
                        walletState.next = page.next || null;
                        walletState.pages = append ? walletState.pages + 1 : 1;
                    });
                } else if (tab === 'admins') {
                    req = adminApiRequest('/api/admin/wallet/admins').then(function (rows) {
                        walletState.admins = rows || [];
                        walletState.rows = walletState.admins; walletState.next = null; walletState.pages = 1;
                    });
                } else {
                    req = adminApiRequest('/api/admin/wallet/activity').then(function (rows) {
                        walletState.rows = rows || []; walletState.next = null; walletState.pages = 1;
                    });
                }
                return req.then(function () { walletShowError(null); renderWalletList(); })
                    .catch(function (e) { walletShowError(e.message || 'Could not load the wallet.'); })
                    .finally(function () { walletState.loading = false; hide(loading); });
            }
'@
$n15 = @'
            function walletLoadList(append) {
                var tab = walletState.tab;
                var loading = document.getElementById('wallet-loading');
                var seq = ++walletState.seq;   // a newer request (tab change, search) makes older answers ignored
                walletState.loading = true;
                if (!append && !walletState.rows.length) show(loading);
                var req;
                if (tab === 'payments' || tab === 'payouts') {
                    var params = { type: tab === 'payments' ? 'credit' : 'debit' };
                    if (walletState.query) params.q = walletState.query;
                    else if (append && walletState.next) params.after = walletState.next;
                    req = adminApiRequest('/api/admin/wallet/transactions', { params: params }).then(function (page) {
                        if (seq !== walletState.seq) return;
                        if (page.error) throw new Error(page.error);
                        walletState.rows = append ? walletState.rows.concat(page.rows || []) : (page.rows || []);
                        walletState.next = page.next || null;
                        walletState.pages = append ? walletState.pages + 1 : 1;
                    });
                } else if (tab === 'admins') {
                    req = adminApiRequest('/api/admin/wallet/admins').then(function (rows) {
                        if (seq !== walletState.seq) return;
                        walletState.admins = rows || [];
                        walletState.rows = walletState.admins; walletState.next = null; walletState.pages = 1;
                    });
                } else {
                    req = adminApiRequest('/api/admin/wallet/activity').then(function (rows) {
                        if (seq !== walletState.seq) return;
                        walletState.rows = rows || []; walletState.next = null; walletState.pages = 1;
                    });
                }
                return req.then(function () {
                        if (seq !== walletState.seq) return;
                        walletState.loading = false; hide(loading);
                        walletShowError(null); renderWalletList();
                    })
                    .catch(function (e) {
                        if (seq !== walletState.seq) return;
                        walletState.loading = false; hide(loading);
                        walletShowError(e.message || 'Could not load the wallet.');
                        renderWalletList();
                    });
            }
'@
Edit $o15 $n15 'load list'

$o16 = @'
                walletState.rows = []; walletState.next = null; walletState.pages = 0;
                document.querySelectorAll('.wallet-tab')
'@
$n16 = @'
                walletState.rows = []; walletState.next = null; walletState.pages = 0;
                walletState.query = '';
                var si = document.getElementById('wallet-search');
                if (si) si.value = '';
                document.querySelectorAll('.wallet-tab')
'@
Edit $o16 $n16 'set tab'

$o17 = @'
if (walletState.pages <= 1) walletLoadList(false);
'@
$n17 = @'
if (walletState.pages <= 1 && !walletState.query) walletLoadList(false);
'@
Edit $o17 $n17 'poll'

$o18 = @'
            document.getElementById('wallet-more').addEventListener('click', function () { walletLoadList(true); });
'@
$n18 = @'
            document.getElementById('wallet-more').addEventListener('click', function () { walletLoadList(true); });
            var walletSearchTimer = null;
            document.getElementById('wallet-search').addEventListener('input', function () {
                var value = this.value.trim();
                clearTimeout(walletSearchTimer);
                walletSearchTimer = setTimeout(function () {
                    if (value === walletState.query) return;
                    walletState.query = value;
                    walletState.rows = []; walletState.next = null; walletState.pages = 0;
                    renderWalletList();
                    walletLoadList(false);
                }, 600);
            });
            document.getElementById('wallet-eye').addEventListener('click', function () {
                walletHidden = !walletHidden;
                try { localStorage.setItem('deenwallet_wallet_hidden', walletHidden ? '1' : '0'); } catch (e) { }
                walletPaintEye();
                renderWalletHero();
                renderWalletList();
            });
            walletPaintEye();
'@
Edit $o18 $n18 'handlers'

$o19 = @'
'The Monime fee (' + pct + ') is worked out for you as soon as you enter an amount.'
'@
$n19 = @'
'The service fee (' + pct + ') is worked out for you as soon as you enter an amount.'
'@
Edit $o19 $n19 'fee preview 1'

$o20 = @'
? 'Monime fee (' + pct + '): ' + wMoney(fee) + '. Total taken from the Monime account: ' + wMoney(minor + fee) + '.'
'@
$n20 = @'
? 'Service fee (' + pct + '): ' + wMoney(fee) + '. Total taken from the DeenWallet balance: ' + wMoney(minor + fee) + '.'
'@
Edit $o20 $n20 'fee preview 2'

$o21 = @'
: 'Monime fee (' + pct + '): ' + wMoney(fee) + ', paid by the business. The receiver gets the full ' + wMoney(minor) + '.';
'@
$n21 = @'
: 'Service fee (' + pct + '): ' + wMoney(fee) + ', paid by the business. The receiver gets the full ' + wMoney(minor) + '.';
'@
Edit $o21 $n21 'fee preview 3'

$o22 = @'
'Monime balance is not available right now.' : 'You can withdraw up to '
'@
$n22 = @'
'The balance is not available right now.' : 'You can withdraw up to '
'@
Edit $o22 $n22 'avail 1'

$o23 = @'
                    : 'Monime balance is not available right now.';
'@
$n23 = @'
                    : 'The balance is not available right now.';
'@
Edit $o23 $n23 'avail 2'

$o24 = @'
                if (currentAdminUser) currentUserRole = currentAdminUser.role || 'ADMIN';
                loginScreen.classList.remove('active');
'@
$n24 = @'
                if (currentAdminUser) currentUserRole = currentAdminUser.role || 'ADMIN';
                // The saved profile only lives for one browser session. After the app was closed it is gone,
                // so fetch it again (this brings back the Admin management menu and the email in the menu).
                adminApiRequest('/api/admin/me').then(function (user) {
                    if (!user) return;
                    currentAdminUser = user;
                    currentUserRole = user.role || 'ADMIN';
                    try { sessionStorage.setItem('deenwallet_admin_profile', JSON.stringify(user)); } catch (e) { }
                    updateAdminDrawerProfile();
                }).catch(function () { });
                loginScreen.classList.remove('active');
'@
Edit $o24 $n24 'profile'

foreach ($f in $newFiles) {
  [System.IO.File]::WriteAllText((Join-Path $root $f[0]), $f[1], $utf8)
  Write-Host ('replaced  ' + $f[0]) -ForegroundColor Green
}
$out = $script:t
if ($htmlCrlf) { $out = $out.Replace("`n", "`r`n") }
[System.IO.File]::WriteAllText((Join-Path $root $htmlRel), $out, $utf8)
Write-Host ('changed   ' + $htmlRel) -ForegroundColor Green
Write-Host ''
Write-Host 'Done. Rebuild: mvn clean package' -ForegroundColor Cyan
