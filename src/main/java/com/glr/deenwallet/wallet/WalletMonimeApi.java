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

    /**
     * The financial-transaction reference of a payout (payout.source.transactionReference). It is empty until the
     * payout has been processed. Used to tie a line in the payments / payouts list back to the DeenWallet record.
     */
    public String payoutReference(String payoutId) {
        requireConfigured();
        String body;
        try {
            body = monime.get().uri("/v1/payouts/{id}", payoutId).retrieve().body(String.class);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("The payment service could not be reached right now.");
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("The payment service refused the request (HTTP " + e.getStatusCode().value() + ").");
        }
        try {
            JsonNode ref = mapper.readTree(body).path("result").path("source").path("transactionReference");
            return ref.isTextual() && !ref.asText().isBlank() ? ref.asText() : null;
        } catch (Exception e) {
            return null;
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
