package com.glr.deenwallet.paylink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glr.deenwallet.transaction.ConversionFee;
import com.glr.deenwallet.transaction.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pay a DeenWallet payment link from a USSD menu (Monime USSD Flow).
 * Monime calls this with an encrypted exchange request; we answer with an encrypted navigate/stop reply.
 * It only reuses PaymentRequestService.publicView / startPayment, so every existing rule (link active, owner allowed,
 * limits, one payer at a time, fee) is applied exactly as on the web page.
 * Switched OFF (404) until USSD_PRIVATE_KEY_FILES in the env file points at one or more PKCS#8 private key files
 * (comma separated, so an old and a new key can both work while keys are rotated).
 */
@RestController
public class UssdPayLinkController {

    private static final Logger log = LoggerFactory.getLogger(UssdPayLinkController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long WINDOW_MS = 600_000L;

    private final PaymentRequestService service;
    private final List<PrivateKey> keys = new ArrayList<>();
    private final Map<String, Deque<Long>> lookups = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> starts = new ConcurrentHashMap<>();
    private final Map<String, Object[]> done = new ConcurrentHashMap<>();

    public UssdPayLinkController(PaymentRequestService service,
                                 @Value("${USSD_PRIVATE_KEY_FILES:}") String files) {
        this.service = service;
        for (String f : files.split(",")) {
            if (f.isBlank()) continue;
            try {
                String pem = Files.readString(Path.of(f.trim()))
                        .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
                keys.add(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))));
            } catch (Exception e) {
                log.warn("USSD key file {} could not be loaded: {}", f.trim(), e.getMessage());
            }
        }
        log.info("USSD payment-link flow: {}", keys.isEmpty() ? "off (no key loaded)" : "on (" + keys.size() + " key(s))");
    }

    @PostMapping(value = "/api/ussd/paylink", produces = "text/plain")
    public ResponseEntity<String> handle(@RequestBody Map<String, String> body) {
        if (keys.isEmpty()) return ResponseEntity.notFound().build();
        byte[] aesKey;
        JsonNode payload;
        try {
            byte[] encKey = Base64.getDecoder().decode(body.get("encryptedAesKey"));
            byte[] encData = Base64.getDecoder().decode(body.get("encryptedExchangeData"));
            aesKey = unwrap(encKey);
            payload = JSON.readTree(decrypt(encData, aesKey));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Bad request");
        }
        Map<String, Object> reply;
        try {
            reply = process(payload);
        } catch (IllegalArgumentException | IllegalStateException | TooManyRequestsException e) {
            reply = stop(e.getMessage());
        } catch (Exception e) {
            log.warn("USSD payment-link error: {}", e.toString());
            reply = stop("Sorry, we could not complete this right now. Please try again in a few minutes.");
        }
        try {
            return ResponseEntity.ok(encrypt(JSON.writeValueAsString(reply), aesKey));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error");
        }
    }

    private Map<String, Object> process(JsonNode p) {
        String page = text(p.path("currentPage"));
        JsonNode flow = p.path("flowData");
        String subscriber = text(p.path("global").path("subscriberId"));
        if (subscriber.isEmpty()) subscriber = text(p.path("global").path("sessionId"));
        String slug = text(flow.path("enter_code")).toLowerCase();

        if ("enter_code".equals(page) || "ask_amount".equals(page)) {
            limit(lookups, subscriber, 30, "Too many attempts. Please wait a few minutes and try again.");
            Map<String, Object> v = service.publicView(slug);
            if (!"ACTIVE".equals(String.valueOf(v.get("status")))) throw new IllegalStateException("This payment link is not accepting payments.");
            String payee = String.valueOf(v.get("payeeName"));
            if (v.get("amountMinor") == null) {
                if ("enter_code".equals(page)) {
                    return navigate("ask_amount", Map.of("payee", clip(payee, 24)));
                }
                long minor = amountMinor(text(flow.path("ask_amount")));
                return navigate("pick_provider", Map.of("summary", summary(payee, minor)));
            }
            return navigate("pick_provider", Map.of("summary", summary(payee, ((Number) v.get("amountMinor")).longValue())));
        }

        if ("confirm".equals(page)) {
            String session = text(p.path("global").path("sessionId"));
            Object[] prev = done.get(session);
            if (prev != null && !session.isEmpty()) {
                @SuppressWarnings("unchecked") Map<String, Object> again = (Map<String, Object>) prev[1];
                return again;
            }
            if (!"true".equalsIgnoreCase(text(flow.path("confirm")))) return stop("Cancelled. Nothing was charged.");
            String phone = phone(text(p.path("exportedData").path("phone")));
            if (phone == null) return stop("We need your phone number to start the payment. Dial again and choose 1 (Yes) when asked to share it.");
            String provider = provider(text(flow.path("pick_provider")));
            if (provider == null) return stop("Please dial again and choose Orange Money or Africell Money.");
            limit(starts, subscriber, 6, "Too many payment attempts. Please wait a few minutes and try again.");
            PaymentRequestService.StartRequest r = new PaymentRequestService.StartRequest();
            r.setProviderId(provider);
            r.setPhone(phone);
            String amt = text(flow.path("ask_amount"));
            if (!amt.isEmpty()) r.setAmount(new BigDecimal(amt.replace(",", "")));
            Map<String, Object> res = service.startPayment(slug, "ussd-" + subscriber, r);
            Object code = res.get("ussdCode");
            long total = ((Number) res.get("totalMinor")).longValue();
            Map<String, Object> out = code == null || String.valueOf(code).isBlank()
                    ? stop("Payment started. Please open the DeenWallet link to finish. Ref " + res.get("reference"))
                    : stop("Dial " + code + " within 10 minutes to pay SLE " + money(total) + " to " + clip(String.valueOf(res.get("payeeName")), 24) + ". Ref " + res.get("reference"));
            if (!session.isEmpty()) {
                if (done.size() > 2000) done.clear();
                done.put(session, new Object[]{System.currentTimeMillis(), out});
            }
            return out;
        }
        return stop("Sorry, this option is not available.");
    }

    private static long amountMinor(String raw) {
        BigDecimal a;
        try { a = new BigDecimal(raw.replace(",", "").trim()); } catch (Exception e) { a = null; }
        if (a == null || a.compareTo(BigDecimal.ONE) < 0 || a.scale() > 2 || a.compareTo(new BigDecimal("100000000")) > 0) {
            throw new IllegalArgumentException("That amount is not valid. Dial again and enter an amount of at least 1.");
        }
        return a.movePointRight(2).longValueExact();
    }

    private static String summary(String payee, long minor) {
        ConversionFee fee = ConversionFee.calculate(minor);
        return "Pay " + clip(payee, 24) + "\nSLE " + money(minor) + " + fee SLE " + money(fee.feeValue()) + "\nTotal SLE " + money(fee.totalChargedValue());
    }

    private static String money(long minor) { return BigDecimal.valueOf(minor, 2).toPlainString(); }

    private static String clip(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) : s); }

    private static String text(JsonNode n) { return n == null || n.isMissingNode() || n.isNull() ? "" : n.asText("").trim(); }

    static String phone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("232")) return "+" + d;
        if (d.length() == 8) return "+232" + d;
        return null;
    }

    static String provider(String v) {
        String s = v == null ? "" : v.trim().toLowerCase();
        if (s.equals("m17") || s.equals("1") || s.startsWith("orange")) return "m17";
        if (s.equals("m18") || s.equals("2") || s.startsWith("africell")) return "m18";
        return null;
    }

    private static void limit(Map<String, Deque<Long>> map, String who, int max, String message) {
        long now = System.currentTimeMillis();
        if (map.size() > 20000) map.clear();
        Deque<Long> q = map.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= max) throw new TooManyRequestsException(message);
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

    private byte[] unwrap(byte[] encKey) throws Exception {
        Exception last = null;
        for (PrivateKey k : keys) {
            try {
                Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
                rsa.init(Cipher.DECRYPT_MODE, k, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
                return rsa.doFinal(encKey);
            } catch (Exception e) { last = e; }
        }
        throw last == null ? new IllegalStateException("no key") : last;
    }

    static byte[] decrypt(byte[] data, byte[] aesKey) throws Exception {
        int ivLen = data[0] & 0xFF;
        byte[] iv = Arrays.copyOfRange(data, 1, 1 + ivLen);
        byte[] ct = Arrays.copyOfRange(data, 1 + ivLen, data.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }

    static String encrypt(String json, byte[] aesKey) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] enc = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[1 + iv.length + enc.length];
        blob[0] = (byte) iv.length;
        System.arraycopy(iv, 0, blob, 1, iv.length);
        System.arraycopy(enc, 0, blob, 1 + iv.length, enc.length);
        return Base64.getEncoder().encodeToString(blob);
    }
}
