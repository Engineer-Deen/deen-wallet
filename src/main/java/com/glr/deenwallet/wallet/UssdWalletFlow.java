package com.glr.deenwallet.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.glr.deenwallet.transaction.TooManyRequestsException;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.slf4j.Logger;
import org.springframework.data.domain.PageRequest;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wallet by USSD for the super admin: check the Main and WAEC pin account balances, and withdraw to a mobile money number.
 * Safety rules (all enforced here, not in the phone menu):
 *  - only a SUPER_ADMIN whose registered phone number is the one dialling (the network supplies the number);
 *  - the wallet PIN is asked to open the menu and again to confirm every withdrawal (two wrong tries disable the PIN
 *    until the admin logs in to the Admin app again, same as in the app);
 *  - one USSD withdrawal is capped (USSD_WALLET_MAX_WITHDRAW, default SLE 5,000); the normal wallet limits still apply;
 *  - the withdrawal itself is WalletService.withdraw(), so fees, daily limit, audit trail and alerts are exactly as in the app.
 * Each page of the Monime flow calls us; we answer with navigate (next page) or stop (final message).
 */
@Component
public class UssdWalletFlow {

    private static final Logger log = LoggerFactory.getLogger(UssdWalletFlow.class);
    private static final long WINDOW_MS = 600_000L;
    private static final long SESSION_MS = 600_000L;

    /** What we remember between the pages of one USSD session (the PIN is never stored). */
    private static final class Sess {
        final long at = System.currentTimeMillis();
        String account = "main";
        String provider, providerName, holder, destination;
        long minor;
    }

    private final WalletService wallet;
    private final UserRepository users;
    private final long maxMinor;
    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();
    private final Map<String, Sess> sessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> done = new ConcurrentHashMap<>();

    public UssdWalletFlow(WalletService wallet, UserRepository users,
                          @Value("${USSD_WALLET_MAX_WITHDRAW:5000}") long maxSle) {
        this.wallet = wallet;
        this.users = users;
        this.maxMinor = Math.max(1, maxSle) * 100L;
    }

    public Map<String, Object> process(JsonNode p) {
        String page = text(p.path("currentPage"));
        JsonNode flow = p.path("flowData");
        String session = text(p.path("global").path("sessionId"));
        String subscriber = text(p.path("global").path("subscriberId"));
        String who = subscriber.isEmpty() ? session : subscriber;
        limit(who, 40);

        User me = caller(subscriber);
        if (me == null) {
            log.info("USSD wallet: caller not recognised as a super admin (subscriber ending {}, page {})", tail(subscriber), page);
            return stop("This service is only for DeenWallet Transfer super admins.");
        }
        try {
            return step(page, flow, session, me);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return stop(e.getReason());
        }
    }

    private Map<String, Object> step(String page, JsonNode flow, String session, User me) {
        if (sessions.size() > 2000) sessions.clear();
        if (done.size() > 2000) done.clear();

        if ("enter_pin".equals(page)) {
            try {
                wallet.verifyPinForUssd(me, text(flow.path("enter_pin")));
            } catch (IllegalArgumentException e) {
                return stop(wrongPin(e) ? "Wrong PIN. Dial again. One more wrong try disables the PIN." : e.getMessage() + " Dial again.");
            } catch (IllegalStateException e) {
                return stop(e.getMessage());
            }
            if (session.isEmpty()) return stop("Please dial again.");
            sessions.put(session, new Sess());
            return navigate("menu", Map.of("name", clip(firstName(me.getFullName()), 18)));
        }

        if ("confirm_pin".equals(page) && !session.isEmpty()) {
            Map<String, Object> prev = done.get(session);      // the network may send the same request twice: answer the same, never withdraw twice
            if (prev != null) return prev;
        }
        Sess s = session.isEmpty() ? null : sessions.get(session);
        if (s == null || System.currentTimeMillis() - s.at > SESSION_MS) {
            sessions.remove(session);
            return stop("Session expired. Please dial again.");
        }

        switch (page) {
            case "menu": {
                String c = text(flow.path("menu"));
                if ("1".equals(c)) return stop(balances(me));
                if ("2".equals(c)) {
                    WalletService.Summary sum = wallet.summary(me);
                    if (!sum.configured() || sum.error() != null) {
                        return stop(sum.error() != null ? sum.error() : "The payment account is not set up on the server.");
                    }
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("main", money(sum.withdrawable() == null ? 0 : sum.withdrawable()));
                    d.put("waec", sum.waecConfigured() && sum.waecWithdrawable() != null ? money(sum.waecWithdrawable()) : "not set up");
                    return navigate("pick_account", d);
                }
                return stop("Goodbye.");
            }
            case "pick_account": {
                String c = text(flow.path("pick_account"));
                if ("1".equals(c)) s.account = "main";
                else if ("2".equals(c)) {
                    if (!wallet.summary(me).waecConfigured()) return stop("The WAEC pin account is not set up.");
                    s.account = "waec";
                } else if (isBack(c)) {
                    return navigate("menu", Map.of("name", clip(firstName(me.getFullName()), 18)));
                } else return stop("Cancelled. Nothing was withdrawn.");
                return navigate("ask_number", Map.of("account", "waec".equals(s.account) ? "WAEC" : "Main"));
            }
            case "ask_number":
            case "number_retry": {
                String raw = text(flow.path(page));
                if (isBack(raw)) return navigate("pick_account", pickData(me));
                WalletService.Detect dt = wallet.detect(me, raw);
                if (!dt.ok()) {
                    return navigate("number_retry", Map.of("error", clip(dt.message(), 80) + " Enter the number, or 0 to go back:"));
                }
                String digits = raw.replaceAll("\\D", "");
                if (digits.startsWith("232") && digits.length() > 8) digits = digits.substring(3);
                if (digits.startsWith("0") && digits.length() > 8) digits = digits.substring(1);
                s.provider = dt.providerId();
                s.providerName = dt.providerName();
                s.holder = dt.holderName() == null || dt.holderName().isBlank() ? "name not shown" : dt.holderName();
                s.destination = "+232" + digits;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("holder", clip(s.holder, 24));
                d.put("network", s.providerName);
                d.put("max", String.valueOf(maxMinor / 100));
                return navigate("ask_amount", d);
            }
            case "ask_amount":
            case "amount_retry": {
                String raw = text(flow.path(page));
                if (s.destination == null) return stop("Session expired. Please dial again.");
                if (isBack(raw)) return navigate("ask_number", Map.of("account", "waec".equals(s.account) ? "WAEC" : "Main"));
                long minor;
                try {
                    minor = amountMinor(raw);
                } catch (IllegalArgumentException e) {
                    return navigate("amount_retry", Map.of("error", "Enter 1 to " + (maxMinor / 100) + " SLE, up to 2 decimals (0 = back):"));
                }
                if (minor > maxMinor) {
                    return navigate("amount_retry", Map.of("error", "USSD limit is SLE " + (maxMinor / 100) + " per withdrawal. Enter a smaller amount (0 = back):"));
                }
                WalletService.Summary sum = wallet.summary(me);
                Long free = "waec".equals(s.account) ? sum.waecWithdrawable() : sum.withdrawable();
                long fee = WalletService.feeOf(minor);
                if (free == null || minor + fee > free) {
                    return navigate("amount_retry", Map.of("error", "Not enough free money (available SLE " + money(free == null ? 0 : free)
                            + ", fee SLE " + money(fee) + "). Enter a smaller amount (0 = back):"));
                }
                s.minor = minor;
                String head = ("waec".equals(s.account) ? "WAEC" : "Main") + " withdraw SLE " + money(minor) + " + fee " + money(fee) + "\n";
                String tail = "\nEnter PIN (0 = back):";
                String num = ", " + s.providerName + " " + s.destination.substring(4);
                int room = Math.max(8, 126 - head.length() - tail.length() - num.length());
                String sumTxt = head + clip(s.holder, room) + num + tail;
                return navigate("confirm_pin", Map.of("summary", sumTxt));
            }
            case "confirm_pin": {
                String pin = text(flow.path("confirm_pin"));
                if (s.destination == null) return stop("Session expired. Please dial again.");
                if (isBack(pin)) {
                    s.minor = 0;
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("holder", clip(s.holder, 24));
                    d.put("network", s.providerName);
                    d.put("max", String.valueOf(maxMinor / 100));
                    return navigate("ask_amount", d);
                }
                if (s.minor <= 0) return stop("Session expired. Please dial again.");
                try {
                    wallet.assertPinUsableForUssd(me);
                    wallet.withdraw(me, "momo", s.provider, s.destination, BigDecimal.valueOf(s.minor, 2), pin, false, s.account);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return stop(wrongPin(e) ? "Wrong PIN. Nothing was withdrawn. Dial again." : e.getMessage() + " Nothing was withdrawn.");
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    throw e;
                } catch (RuntimeException e) {
                    log.warn("USSD wallet withdraw error: {}", e.toString());
                    return stop("Could not confirm. Check the Admin app wallet before trying again.");
                }
                Map<String, Object> out = stop("Withdrawal of SLE " + money(s.minor) + " to " + clip(s.holder, 20) + " ("
                        + s.providerName + ") started. Check the Admin app for the result.");
                done.put(session, out);
                sessions.remove(session);
                return out;
            }
            default:
                return stop("Sorry, this option is not available.");
        }
    }

    /** 0 or 00 on any step means "go back one step". */
    private static boolean isBack(String v) {
        return "0".equals(v) || "00".equals(v);
    }

    private Map<String, Object> pickData(User me) {
        WalletService.Summary sum = wallet.summary(me);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("main", money(sum.withdrawable() == null ? 0 : sum.withdrawable()));
        d.put("waec", sum.waecConfigured() && sum.waecWithdrawable() != null ? money(sum.waecWithdrawable()) : "not set up");
        return d;
    }

    private static boolean wrongPin(RuntimeException e) {
        return e.getMessage() != null && e.getMessage().startsWith("Wrong PIN");
    }

    private String balances(User me) {
        WalletService.Summary s = wallet.summary(me);
        if (!s.configured()) return "The payment account is not set up on the server.";
        if (s.error() != null) return s.error();
        String waec = s.waecConfigured() && s.waecAvailable() != null
                ? "SLE " + money(s.waecAvailable()) + "\nFree: " + money(s.waecWithdrawable() == null ? 0 : s.waecWithdrawable())
                : "not set up";
        return "Main: SLE " + money(s.monimeAvailable() == null ? 0 : s.monimeAvailable())
                + "\nFree: " + money(s.withdrawable() == null ? 0 : s.withdrawable())
                + "\nWAEC: " + waec;
    }

    /**
     * The caller must be an active super admin. Monime does not send the phone number; it sends subscriberId, a UUID (version 5,
     * URL namespace) made from the number in international form, so we compute the same ID from each super admin's registered phone.
     */
    private User caller(String subscriberId) {
        if (subscriberId == null || subscriberId.isBlank()) return null;
        for (User u : users.findByRole("SUPER_ADMIN", PageRequest.of(0, 50))) {
            if (!u.isActive() || u.isLocked() || u.getPhone() == null) continue;
            String ph = u.getPhone().trim();
            if (!ph.startsWith("+")) ph = "+" + ph;
            if (subscriberId.equalsIgnoreCase(uuidV5Url(ph))) return u;
        }
        return null;
    }

    private static final byte[] URL_NAMESPACE = {
            0x6b, (byte) 0xa7, (byte) 0xb8, 0x11, (byte) 0x9d, (byte) 0xad, 0x11, (byte) 0xd1,
            (byte) 0x80, (byte) 0xb4, 0x00, (byte) 0xc0, 0x4f, (byte) 0xd4, 0x30, (byte) 0xc8 };

    /** UUID version 5 (SHA-1) in the URL namespace, the same way Monime makes subscriberId. */
    static String uuidV5Url(String name) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(URL_NAMESPACE);
            md.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] h = md.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
                sb.append(String.format("%02x", h[i] & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers
    private static String latest(JsonNode flow, String retryPage, String firstPage) {
        String r = text(flow.path(retryPage));
        return r.isEmpty() ? text(flow.path(firstPage)) : r;
    }

    private static long amountMinor(String raw) {
        BigDecimal a;
        try { a = new BigDecimal(raw.replace(",", "").trim()); } catch (Exception e) { a = null; }
        if (a == null || a.compareTo(BigDecimal.ONE) < 0 || a.scale() > 2 || a.compareTo(new BigDecimal("100000000")) > 0) {
            throw new IllegalArgumentException("Enter an amount of 1 or more, up to 2 decimals.");
        }
        return a.movePointRight(2).longValueExact();
    }

    private static String money(long minor) { return String.format(Locale.US, "%,.2f", BigDecimal.valueOf(minor, 2)); }

    private static String clip(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) : s); }

    private static String text(JsonNode n) { return n == null || n.isMissingNode() || n.isNull() ? "" : n.asText("").trim(); }

    private static String tail(String s) { return s == null || s.length() < 4 ? "" : s.substring(s.length() - 4); }

    private static String firstName(String full) {
        if (full == null || full.isBlank()) return "";
        String f = full.trim().split("\\s+")[0];
        return f;
    }

    private void limit(String who, int max) {
        long now = System.currentTimeMillis();
        if (calls.size() > 20000) calls.clear();
        Deque<Long> q = calls.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= max) throw new TooManyRequestsException("Too many attempts. Please wait a few minutes and try again.");
            q.addLast(now);
        }
    }

    private static Map<String, Object> stop(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "stop");
        m.put("message", clip(message == null || message.isBlank() ? "Sorry, something went wrong." : message, 150));
        return m;
    }

    private static Map<String, Object> navigate(String pageId, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "navigate");
        m.put("pageId", pageId);
        m.put("pageData", data);
        return m;
    }
}
