package com.glr.deenwallet.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.glr.deenwallet.kyc.KycLimitService;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.safety.MoneySafetyService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "Send to a crypto wallet": the customer pays with Orange Money / Africell Money and HoneyCoin sends USDT to the wallet
 * address they give. DeenWallet never holds the money or the crypto: HoneyCoin charges the customer's phone directly.
 * Everything is OFF until CRYPTO_ENABLED=true. Limits (env): CRYPTO_MIN_MINOR, CRYPTO_MAX_MINOR, CRYPTO_DAILY_LIMIT_MINOR,
 * CRYPTO_REQUIRE_KYC (default true: only ID-verified customers).
 */
@Service
public class CryptoOrderService {

    private static final Logger log = LoggerFactory.getLogger(CryptoOrderService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern EVM_ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** Networks HoneyCoin settles on. BSC is the one Binance calls "BNB Smart Chain (BEP20)". */
    private static final Map<String, String> CHAINS = new LinkedHashMap<>();
    static {
        CHAINS.put("BSC", "BNB Smart Chain (BEP20) - use this for Binance");
        CHAINS.put("ETH", "Ethereum (ERC20)");
        CHAINS.put("MATIC", "Polygon");
        CHAINS.put("BASE", "Base");
        CHAINS.put("ARB", "Arbitrum One");
        CHAINS.put("OPTIMISM", "Optimism");
    }
    private static final Map<String, String> OPERATORS = new LinkedHashMap<>();
    static {
        OPERATORS.put("orange", "Orange Money");
        OPERATORS.put("africell", "Africell Money");
    }

    private static final String COLUMNS = "o.id, o.reference, o.amount_minor, o.operator, o.asset, o.chain, o.address, o.status, "
            + "o.crypto_amount, o.chain_tx_id, o.failure_reason, CAST(EXTRACT(EPOCH FROM o.created_at) * 1000 AS bigint), "
            + "CAST(EXTRACT(EPOCH FROM o.updated_at) * 1000 AS bigint), o.provider_tx_id, o.user_id, o.phone";

    private final HoneyCoinClient honey;
    private final KycLimitService kyc;
    private final UserRepository users;
    private final MoneySafetyService safety;
    private final TransactionTemplate tx;
    private final NotificationService notifications;
    private final PushNotificationService push;

    @PersistenceContext
    private EntityManager em;

    @Value("${CRYPTO_ENABLED:false}")
    private boolean enabled;
    @Value("${CRYPTO_MIN_MINOR:500}")
    private long minMinor;
    @Value("${CRYPTO_MAX_MINOR:200000}")
    private long maxMinor;
    @Value("${CRYPTO_DAILY_LIMIT_MINOR:500000}")
    private long dailyMinor;
    @Value("${CRYPTO_REQUIRE_KYC:true}")
    private boolean requireKyc;
    @Value("${HONEYCOIN_WEBHOOK_SECRET:}")
    private String webhookSecret;

    private final Map<UUID, Object> userLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChecked = new ConcurrentHashMap<>();

    public CryptoOrderService(HoneyCoinClient honey, KycLimitService kyc, UserRepository users,
                              MoneySafetyService safety, TransactionTemplate tx, NotificationService notifications,
                              PushNotificationService push) {
        this.honey = honey;
        this.kyc = kyc;
        this.users = users;
        this.safety = safety;
        this.tx = tx;
        this.notifications = notifications;
        this.push = push;
    }

    // ------------------------------------------------------------------ requests

    public static class CreateRequest {
        private BigDecimal amount;
        private String operator;
        private String chain;
        private String address;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal v) { this.amount = v; }
        public String getOperator() { return operator; }
        public void setOperator(String v) { this.operator = v; }
        public String getChain() { return chain; }
        public void setChain(String v) { this.chain = v; }
        public String getAddress() { return address; }
        public void setAddress(String v) { this.address = v; }
    }

    // ------------------------------------------------------------------ customer side

    /** What the screen needs: is it on, who may use it, the limits, the networks and the customer's own number. */
    public Map<String, Object> config(UUID userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled && honey.configured());
        m.put("sandbox", honey.sandbox());
        boolean verified = kyc.isVerified(userId);
        m.put("needsVerification", requireKyc && !verified);
        m.put("minMinor", minMinor);
        m.put("maxMinor", maxMinor);
        m.put("dailyLimitMinor", dailyMinor);
        m.put("dailyLeftMinor", Math.max(0, dailyMinor - usedLast24h(userId)));
        m.put("asset", "USDT");
        List<Map<String, String>> chains = new ArrayList<>();
        for (Map.Entry<String, String> e : CHAINS.entrySet()) chains.add(Map.of("id", e.getKey(), "name", e.getValue()));
        m.put("chains", chains);
        List<Map<String, String>> ops = new ArrayList<>();
        for (Map.Entry<String, String> e : OPERATORS.entrySet()) ops.add(Map.of("id", e.getKey(), "name", e.getValue()));
        m.put("operators", ops);
        User u = users.findById(userId).orElse(null);
        m.put("phone", u == null ? null : maskPhone(u.getPhone()));
        return m;
    }

    /** Indicative price only. The final amount is set by HoneyCoin when the payment is made. */
    public Map<String, Object> quote(UUID userId, BigDecimal amount) {
        requireEnabled();
        long minor = toMinor(amount);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountMinor", minor);
        m.put("asset", "USDT");
        Double rate = honey.fxRate("USDT");
        Double est = null;
        if (rate != null) {
            double sle = minor / 100.0;
            est = rate > 1 ? sle / rate : sle * rate;   // a rate above 1 means leones per USDT
        }
        m.put("estimatedCrypto", est == null ? null : Math.floor(est * 100) / 100.0);
        m.put("note", "About this much. HoneyCoin's rate and fee at the time of payment decide the final amount.");
        return m;
    }

    public Map<String, Object> create(UUID userId, CreateRequest b) {
        requireEnabled();
        safety.requireOpenForCustomers();
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!u.isActive() || u.isLocked() || !u.isEmailVerified()) {
            throw new IllegalStateException("Your account cannot use this service right now.");
        }
        if (requireKyc && !kyc.isVerified(userId)) {
            throw new IllegalStateException("Verify your identity (ID check) in Profile to use this service.");
        }
        long minor = toMinor(b.getAmount());
        String operator = b.getOperator() == null ? "" : b.getOperator().trim().toLowerCase();
        if (!OPERATORS.containsKey(operator)) throw new IllegalArgumentException("Choose Orange Money or Africell Money.");
        String chain = b.getChain() == null ? "" : b.getChain().trim().toUpperCase();
        if (!CHAINS.containsKey(chain)) throw new IllegalArgumentException("Choose the network your wallet uses.");
        String address = b.getAddress() == null ? "" : b.getAddress().trim();
        if (!EVM_ADDRESS.matcher(address).matches() || address.matches("^0x0{40}$")) {
            throw new IllegalArgumentException("That wallet address is not valid for this network. Copy it again from your wallet.");
        }
        String digits = u.getPhone() == null ? "" : u.getPhone().replaceAll("\\D", "");
        if (!digits.startsWith("232") || digits.length() != 11) {
            throw new IllegalStateException("Your registered phone number must be a Sierra Leone number to pay this way.");
        }
        // The customer confirms with their PIN in the app (like every transfer) and then must approve the charge on their own phone.

        final String reference;
        final UUID id = UUID.randomUUID();
        synchronized (userLocks.computeIfAbsent(userId, k -> new Object())) {
            long open = count("SELECT COUNT(*) FROM crypto_orders WHERE user_id = :u AND status IN ('CREATED','PENDING') "
                    + "AND created_at > now() - interval '6 hours'", "u", userId);
            if (open >= 2) throw new IllegalStateException("You already have crypto payments waiting. Approve or wait for them to finish first.");
            if (usedLast24h(userId) + minor > dailyMinor) {
                throw new IllegalStateException("This is above your daily limit for crypto. You can send up to SLE "
                        + money(Math.max(0, dailyMinor - usedLast24h(userId))) + " more today.");
            }
            reference = newReference();
            tx.executeWithoutResult(st -> em.createNativeQuery(
                    "INSERT INTO crypto_orders (id, user_id, reference, amount_minor, operator, phone, asset, chain, address, status) "
                            + "VALUES (:id, :u, :r, :a, :o, :p, 'USDT', :c, :ad, 'CREATED')")
                    .setParameter("id", id).setParameter("u", userId).setParameter("r", reference).setParameter("a", minor)
                    .setParameter("o", operator).setParameter("p", "+" + digits).setParameter("c", chain).setParameter("ad", address)
                    .executeUpdate());
        }

        final String providerId;
        try {
            providerId = honey.createOnramp(minor / 100.0, "USDT", chain, address, digits, operator, reference, u.getEmail());
        } catch (HoneyCoinClient.HoneyCoinException e) {
            if (e.definite()) {
                setFailed(id, "The payment provider refused this payment.");
                throw new IllegalStateException("We could not start this payment. Nothing was charged. Please try again later.");
            }
            // Unknown outcome: the webhook (matched by reference) or the background check settles it either way.
            log.warn("Crypto order {} could not be confirmed with the provider: {}", reference, e.getMessage());
            throw new IllegalStateException("We could not confirm this payment request. If a payment prompt reaches your phone you can approve it; "
                    + "otherwise nothing is charged. Check the history in a few minutes.");
        }
        tx.executeWithoutResult(st -> em.createNativeQuery(
                "UPDATE crypto_orders SET provider_tx_id = :p, status = 'PENDING', updated_at = now() WHERE id = :id AND status = 'CREATED'")
                .setParameter("p", providerId).setParameter("id", id).executeUpdate());
        return view(loadOwned(userId, id));
    }

    public Map<String, Object> status(UUID userId, UUID id) {
        Object[] row = loadOwned(userId, id);
        String st = String.valueOf(row[7]);
        if (("PENDING".equals(st) || "CREATED".equals(st)) && row[13] != null) {
            long now = System.currentTimeMillis();
            Long last = lastChecked.get(id);
            if (last == null || now - last > 4000) {
                if (lastChecked.size() > 2000) lastChecked.clear();
                lastChecked.put(id, now);
                refreshFromProvider(id);
                row = loadOwned(userId, id);
            }
        }
        return view(row);
    }

    public List<Map<String, Object>> history(UUID userId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS + " FROM crypto_orders o WHERE o.user_id = :u ORDER BY o.created_at DESC LIMIT 30")
                .setParameter("u", userId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(view(r));
        return out;
    }

    // ------------------------------------------------------------------ provider side

    /** The provider's webhook. Its "signature" header carries the shared secret. The webhook only triggers a check: success is never taken from it. */
    public void handleWebhook(String headerSecret, JsonNode body) {
        if (webhookSecret == null || webhookSecret.isBlank() || headerSecret == null
                || !MessageDigest.isEqual(webhookSecret.getBytes(StandardCharsets.UTF_8), headerSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Bad webhook secret");
        }
        String event = body.path("event").asText("");
        if (!event.equals("onramp_created") && !event.equals("onramp_updated")) return;
        JsonNode d = body.path("data");
        String ref = d.path("externalReference").asText("");
        String providerId = d.path("transactionId").asText("");
        if (ref.isBlank()) return;
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery("SELECT CAST(id AS text) FROM crypto_orders WHERE reference = :r").setParameter("r", ref).getResultList();
        if (ids.isEmpty()) {
            safety.alertOnce("cryptounmatched:" + ref, "CRYPTO_UNMATCHED", "Crypto payment with no matching order",
                    "The crypto provider reported reference " + ref + " but there is no order for it here. Check the HoneyCoin dashboard.");
            return;
        }
        UUID id = UUID.fromString(String.valueOf(ids.get(0)));
        if (!providerId.isBlank()) {
            tx.executeWithoutResult(st -> em.createNativeQuery(
                    "UPDATE crypto_orders SET provider_tx_id = COALESCE(provider_tx_id, :p), "
                            + "status = CASE WHEN status = 'CREATED' THEN 'PENDING' ELSE status END, updated_at = now() WHERE id = :id")
                    .setParameter("p", providerId).setParameter("id", id).executeUpdate());
        }
        refreshFromProvider(id);
    }

    /** Asks the provider what really happened and records it. Safe to call any time, any number of times. */
    public void refreshFromProvider(UUID id) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> p = em.createNativeQuery("SELECT provider_tx_id FROM crypto_orders WHERE id = :id").setParameter("id", id).getResultList();
            if (p.isEmpty() || p.get(0) == null) return;
            HoneyCoinClient.ProviderTx t = honey.getTransaction(String.valueOf(p.get(0)));
            apply(id, t);
        } catch (RuntimeException e) {
            log.warn("Could not check crypto order {} with the provider: {}", id, e.toString());
        }
    }

    private void apply(UUID id, HoneyCoinClient.ProviderTx t) {
        final String[] was = new String[1];
        final Object[][] info = new Object[1][];
        tx.executeWithoutResult(st -> {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("SELECT status, user_id, reference, amount_minor FROM crypto_orders WHERE id = :id FOR UPDATE")
                    .setParameter("id", id).getResultList();
            if (rows.isEmpty()) return;
            Object[] r = rows.get(0);
            String cur = String.valueOf(r[0]);
            if (cur.equals("SUCCESSFUL")) return;
            if (t.state().equals("SUCCESSFUL")) {
                em.createNativeQuery("UPDATE crypto_orders SET status = 'SUCCESSFUL', crypto_amount = :c, chain_tx_id = :x, failure_reason = NULL, "
                                + "completed_at = now(), updated_at = now() WHERE id = :id")
                        .setParameter("c", t.cryptoAmount()).setParameter("x", t.chainTxId()).setParameter("id", id).executeUpdate();
                was[0] = cur;
                info[0] = r;
            } else if (t.state().equals("FAILED") && (cur.equals("CREATED") || cur.equals("PENDING"))) {
                em.createNativeQuery("UPDATE crypto_orders SET status = 'FAILED', failure_reason = :f, completed_at = now(), updated_at = now() WHERE id = :id")
                        .setParameter("f", clip(t.failureReason() == null ? "The payment was not completed." : t.failureReason(), 280))
                        .setParameter("id", id).executeUpdate();
                was[0] = cur;
                info[0] = r;
            } else if (t.state().equals("PENDING") && cur.equals("CREATED")) {
                em.createNativeQuery("UPDATE crypto_orders SET status = 'PENDING', updated_at = now() WHERE id = :id").setParameter("id", id).executeUpdate();
            }
        });
        if (was[0] == null || info[0] == null) return;
        UUID userId = UUID.fromString(String.valueOf(info[0][1]));
        String ref = String.valueOf(info[0][2]);
        if (t.state().equals("SUCCESSFUL")) {
            if (was[0].equals("FAILED")) {
                safety.alert("CRYPTO_LATE_SUCCESS", "Crypto payment succeeded after it was marked failed",
                        "Order " + ref + " was marked failed but the provider says it was paid and the crypto was sent. It is now shown as completed.");
            }
            notifyUser(userId, ref, "CRYPTO_SENT", "Crypto sent",
                    "Your crypto payment " + ref + " is complete" + (t.cryptoAmount() == null ? "." : ": " + t.cryptoAmount() + " USDT is on its way to your wallet."), id);
        } else {
            notifyUser(userId, ref, "CRYPTO_FAILED", "Crypto payment not completed",
                    "Your crypto payment " + ref + " was not completed. If money left your mobile money account, contact support with this reference.", id);
        }
    }

    private void setFailed(UUID id, String reason) {
        tx.executeWithoutResult(st -> em.createNativeQuery(
                "UPDATE crypto_orders SET status = 'FAILED', failure_reason = :f, completed_at = now(), updated_at = now() WHERE id = :id AND status IN ('CREATED','PENDING')")
                .setParameter("f", reason).setParameter("id", id).executeUpdate());
    }

    private void notifyUser(UUID userId, String ref, String type, String title, String msg, UUID orderId) {
        try {
            notifications.create(userId, type, title, msg, null, ref);
            push.sendToUser(userId, title, msg, Map.of("type", type, "orderId", orderId.toString()));
        } catch (RuntimeException e) {
            log.warn("Could not notify {} about crypto order {}: {}", userId, ref, e.toString());
        }
    }

    /** Every two minutes: check orders still waiting and close ones that can no longer complete. Works whenever keys exist, even if the service was switched off. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void reconcile() {
        if (!honey.configured()) return;
        try {
            @SuppressWarnings("unchecked")
            List<Object> waiting = em.createNativeQuery("SELECT CAST(id AS text) FROM crypto_orders WHERE status = 'PENDING' "
                    + "AND provider_tx_id IS NOT NULL AND updated_at < now() - interval '20 seconds' ORDER BY created_at LIMIT 30").getResultList();
            for (Object o : waiting) refreshFromProvider(UUID.fromString(String.valueOf(o)));
            // Still waiting after 6 hours: show it as not completed (a late success from the provider still turns it to completed).
            tx.executeWithoutResult(st -> em.createNativeQuery(
                    "UPDATE crypto_orders SET status = 'FAILED', failure_reason = 'The payment was not approved in time.', completed_at = now(), updated_at = now() "
                            + "WHERE status = 'PENDING' AND created_at < now() - interval '6 hours'").executeUpdate());
            // Never confirmed by the provider within 30 minutes.
            @SuppressWarnings("unchecked")
            List<Object> lost = em.createNativeQuery("SELECT reference FROM crypto_orders WHERE status = 'CREATED' AND created_at < now() - interval '30 minutes'").getResultList();
            if (!lost.isEmpty()) {
                tx.executeWithoutResult(st -> em.createNativeQuery(
                        "UPDATE crypto_orders SET status = 'FAILED', failure_reason = 'The payment provider did not confirm this request.', completed_at = now(), updated_at = now() "
                                + "WHERE status = 'CREATED' AND created_at < now() - interval '30 minutes'").executeUpdate());
                safety.alertOnce("cryptolost", "CRYPTO_UNCONFIRMED", "Crypto request not confirmed by the provider",
                        lost.size() + " crypto order(s) were never confirmed by HoneyCoin and were closed: " + lost
                                + ". Check the HoneyCoin dashboard in case any was charged.");
            }
        } catch (RuntimeException e) {
            log.warn("Crypto reconcile run failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireEnabled() {
        if (!enabled || !honey.configured()) throw new IllegalStateException("Sending to a crypto wallet is coming soon.");
    }

    private long toMinor(BigDecimal a) {
        if (a == null || a.scale() > 2 || a.signum() <= 0) throw new IllegalArgumentException("Enter an amount in leones (up to 2 decimal places).");
        long minor = a.movePointRight(2).longValueExact();
        if (minor < minMinor) throw new IllegalArgumentException("The smallest amount is SLE " + money(minMinor) + ".");
        if (minor > maxMinor) throw new IllegalArgumentException("The most you can send at once is SLE " + money(maxMinor) + ".");
        return minor;
    }

    private long usedLast24h(UUID userId) {
        return count("SELECT COALESCE(SUM(amount_minor), 0) FROM crypto_orders WHERE user_id = :u AND created_at > now() - interval '24 hours' "
                + "AND status IN ('CREATED','PENDING','SUCCESSFUL')", "u", userId);
    }

    private long count(String sql, String name, Object value) {
        return ((Number) em.createNativeQuery(sql).setParameter(name, value).getSingleResult()).longValue();
    }

    private String newReference() {
        for (int attempt = 0; attempt < 12; attempt++) {
            StringBuilder sb = new StringBuilder("CR");
            for (int i = 0; i < 8; i++) sb.append(REF_CHARS.charAt(RANDOM.nextInt(REF_CHARS.length())));
            String r = sb.toString();
            if (count("SELECT COUNT(*) FROM crypto_orders WHERE reference = :r", "r", r) == 0) return r;
        }
        throw new IllegalStateException("Could not create the request right now. Please try again.");
    }

    private Object[] loadOwned(UUID userId, UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT " + COLUMNS + " FROM crypto_orders o WHERE o.id = :id AND o.user_id = :u")
                .setParameter("id", id).setParameter("u", userId).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Payment not found.");
        return rows.get(0);
    }

    /** What the customer sees. */
    private Map<String, Object> view(Object[] r) {
        String st = String.valueOf(r[7]);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r[0]));
        m.put("reference", r[1]);
        m.put("amountMinor", ((Number) r[2]).longValue());
        m.put("operator", OPERATORS.getOrDefault(String.valueOf(r[3]), String.valueOf(r[3])));
        m.put("asset", r[4]);
        m.put("chain", r[5]);
        m.put("chainName", CHAINS.getOrDefault(String.valueOf(r[5]), String.valueOf(r[5])));
        m.put("address", r[6]);
        m.put("status", st);
        String phase;
        String message;
        switch (st) {
            case "SUCCESSFUL":
                phase = "done";
                message = "Done. Your USDT has been sent to your wallet.";
                break;
            case "FAILED":
                phase = "failed";
                message = r[10] == null ? "This payment was not completed." : String.valueOf(r[10]);
                break;
            default:
                phase = "waiting";
                message = "Check your phone and approve the payment request from " + OPERATORS.getOrDefault(String.valueOf(r[3]), "your mobile money") + ".";
        }
        m.put("phase", phase);
        m.put("message", message);
        m.put("cryptoAmount", r[8]);
        m.put("chainTxId", r[9]);
        m.put("createdAt", ((Number) r[11]).longValue());
        m.put("updatedAt", ((Number) r[12]).longValue());
        return m;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 4) + " ••• ••" + phone.substring(phone.length() - 3);
    }

    private static String money(long minor) {
        return String.format("%,.2f", minor / 100.0);
    }

    private static String clip(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }
}
