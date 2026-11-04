package com.glr.deenwallet.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HoneyCoin (crypto on-ramp): the customer pays with mobile money and HoneyCoin sends USDT/USDC to their wallet.
 * Settings (all in deenwallet.env): HONEYCOIN_PUBLIC_KEY, HONEYCOIN_API_KEY, HONEYCOIN_SANDBOX (default true: set false only
 * for real money), HONEYCOIN_API_BASE, HONEYCOIN_CRYPTO_BASE, HONEYCOIN_AUTH_URL (optional override).
 * Written from HoneyCoin's published docs; the response shapes are read leniently. Verify against the sandbox before go-live.
 */
@Service
public class HoneyCoinClient {

    private static final Logger log = LoggerFactory.getLogger(HoneyCoinClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${HONEYCOIN_PUBLIC_KEY:}")
    private String publicKey;
    @Value("${HONEYCOIN_API_KEY:}")
    private String apiKey;
    @Value("${HONEYCOIN_SANDBOX:true}")
    private boolean sandbox;
    @Value("${HONEYCOIN_API_BASE:https://api-v2.honeycoin.app/api}")
    private String apiBase;
    @Value("${HONEYCOIN_CRYPTO_BASE:https://crypto.honeycoin.app/api}")
    private String cryptoBase;
    @Value("${HONEYCOIN_AUTH_URL:}")
    private String authUrl;

    private volatile RestClient http;
    private String token;
    private long tokenUntil;

    /** A failed call. definite = the provider clearly refused it (so nothing was started); otherwise the outcome is unknown. */
    public static class HoneyCoinException extends RuntimeException {
        private final int status;
        private final boolean definite;

        public HoneyCoinException(int status, String message, boolean definite) {
            super(message);
            this.status = status;
            this.definite = definite;
        }

        public int status() { return status; }
        public boolean definite() { return definite; }
    }

    /** What the provider says about one on-ramp. state is PENDING, SUCCESSFUL or FAILED. */
    public record ProviderTx(String transactionId, String state, String cryptoAmount, String chainTxId, String failureReason) { }

    public boolean configured() {
        return publicKey != null && !publicKey.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    public boolean sandbox() { return sandbox; }

    private static String trim(String s) { return s == null ? "" : s.replaceAll("/+$", ""); }

    private String b2b() { return trim(apiBase) + (sandbox ? "/sandbox/b2b" : "/b2b"); }

    private String minting() { return trim(cryptoBase) + (sandbox ? "/sandbox/minting" : "/minting"); }

    private RestClient client() {
        RestClient c = http;
        if (c == null) {
            HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
            factory.setReadTimeout(Duration.ofSeconds(20));
            c = RestClient.builder().requestFactory(factory).build();
            http = c;
        }
        return c;
    }

    private synchronized String token() {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenUntil) return token;
        String url = authUrl == null || authUrl.isBlank() ? b2b() + "/auth/generate-bearer-token" : authUrl.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicKey", publicKey);
        JsonNode r = call(HttpMethod.POST, url, body, false);
        String t = findToken(r);
        if (t == null) throw new HoneyCoinException(-1, "No token in the provider's sign-in response", false);
        token = t;
        tokenUntil = now + 50L * 60_000L;   // tokens last 1 hour
        return t;
    }

    private synchronized void forgetToken() { token = null; tokenUntil = 0; }

    private static String findToken(JsonNode r) {
        String[][] paths = {{"data", "token"}, {"token"}, {"data", "bearerToken"}, {"bearerToken"},
                {"data", "accessToken"}, {"accessToken"}, {"data", "access_token"}, {"access_token"}};
        for (String[] p : paths) {
            JsonNode n = r;
            for (String k : p) n = n == null ? null : n.get(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) return n.asText();
        }
        JsonNode d = r.get("data");
        return d != null && d.isTextual() && !d.asText().isBlank() ? d.asText() : null;
    }

    private JsonNode call(HttpMethod method, String url, Object body, boolean authed) {
        try {
            RestClient.RequestBodySpec spec = client().method(method).uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            if (authed) spec = spec.header("Authorization", "Bearer " + token());
            else spec = spec.header("api-key", apiKey);
            if (body != null) spec = spec.body(body);
            String raw = spec.retrieve().body(String.class);
            return raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            if (s == 401 && authed) forgetToken();
            boolean definite = s >= 400 && s < 500 && s != 408 && s != 429;
            String text = e.getResponseBodyAsString();
            log.warn("HoneyCoin {} {} answered HTTP {}: {}", method, url, s, text == null ? "" : (text.length() > 300 ? text.substring(0, 300) : text));
            throw new HoneyCoinException(s, "HoneyCoin answered HTTP " + s, definite);
        } catch (ResourceAccessException e) {
            throw new HoneyCoinException(0, "HoneyCoin could not be reached: " + e.getMessage(), false);
        } catch (JsonProcessingException e) {
            throw new HoneyCoinException(-1, "HoneyCoin sent an unreadable answer", false);
        }
    }

    /** Authenticated call that signs in again once if the token was refused. */
    private JsonNode authed(HttpMethod method, String url, Object body) {
        try {
            return call(method, url, body, true);
        } catch (HoneyCoinException e) {
            if (e.status() != 401) throw e;
            return call(method, url, body, true);
        }
    }

    /** Starts an on-ramp: the customer is charged by mobile money, then the stablecoin is sent to the wallet. Returns the provider's transaction id. */
    public String createOnramp(double senderAmount, String asset, String chain, String address, String phoneDigits,
                               String operatorId, String reference, String email) {
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("phoneNumber", phoneDigits);
        charge.put("momoOperatorId", operatorId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderAmount", senderAmount);
        body.put("senderCurrency", "SLE");
        body.put("receiverCurrency", asset);
        body.put("chain", chain);
        body.put("receiverAddress", address);
        body.put("method", "momo");
        body.put("chargeDetails", charge);
        body.put("externalReference", reference);
        if (email != null && !email.isBlank()) body.put("email", email);
        JsonNode r = authed(HttpMethod.POST, minting() + "/onramp", body);
        JsonNode ok = r.get("success");
        if (ok != null && ok.isBoolean() && !ok.asBoolean()) {
            throw new HoneyCoinException(400, "HoneyCoin refused the request", true);
        }
        JsonNode id = r.path("data").path("transactionId");
        if (id.isMissingNode() || id.asText().isBlank()) id = r.path("transactionId");
        if (id.isMissingNode() || id.asText().isBlank()) throw new HoneyCoinException(-1, "HoneyCoin did not return a transaction id", false);
        return id.asText();
    }

    /** Reads the provider's own record of an on-ramp. SUCCESSFUL only when both the status and the charge status say so. */
    public ProviderTx getTransaction(String transactionId) {
        JsonNode r = authed(HttpMethod.GET, b2b() + "/transactions/" + URLEncoder.encode(transactionId, StandardCharsets.UTF_8), null);
        JsonNode d = r.has("data") ? r.get("data") : r;
        String status = d.path("status").asText("").toLowerCase();
        String charge = d.path("chargeStatus").asText("").toLowerCase();
        String state;
        if (status.equals("failed") || charge.equals("failed")) state = "FAILED";
        else if (status.equals("successful") && charge.equals("successful")) state = "SUCCESSFUL";
        else state = "PENDING";
        String crypto = d.path("cryptoAmount").isMissingNode() ? null : d.path("cryptoAmount").asText(null);
        String tx = d.path("txId").isMissingNode() ? null : d.path("txId").asText(null);
        String why = d.path("failureReason").isMissingNode() ? null : d.path("failureReason").asText(null);
        return new ProviderTx(transactionId, state, crypto, tx, why);
    }

    /** Indicative rate between leones and a stablecoin, or null when unavailable. Never used to move money. */
    public Double fxRate(String asset) {
        try {
            JsonNode r = authed(HttpMethod.GET, b2b() + "/fx/rate?from=SLE&to=" + URLEncoder.encode(asset, StandardCharsets.UTF_8), null);
            JsonNode d = r.has("data") ? r.get("data") : r;
            for (String k : new String[]{"rate", "exchangeRate", "price"}) {
                JsonNode n = d.get(k);
                if (n != null && n.isNumber() && n.asDouble() > 0) return n.asDouble();
                if (n != null && n.isTextual()) {
                    try { double v = Double.parseDouble(n.asText()); if (v > 0) return v; } catch (NumberFormatException ignore) { }
                }
            }
        } catch (RuntimeException e) {
            log.debug("HoneyCoin rate unavailable: {}", e.toString());
        }
        return null;
    }
}
