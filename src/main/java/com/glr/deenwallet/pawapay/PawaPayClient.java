package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

/**
 * pawaPay Merchant API v2. Every "initiate" call carries an id WE created and saved first (depositId, remittanceId,
 * refundId), so repeating a call is always safe (pawaPay answers DUPLICATE_IGNORED).
 *
 * A normal answer comes back as a PawaPayResponse (HTTP 200 can still say REJECTED - always read status()).
 * Timeouts, network errors and HTTP 5xx throw PawaPayUnavailableException: the outcome is UNKNOWN, so check the status.
 */
@Slf4j
@Service
public class PawaPayClient {

    private final PawaPayProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http;

    public PawaPayClient(PawaPayProperties props) {
        this.props = props;
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(20));
        String base = props.baseUrl() == null || props.baseUrl().isBlank() ? "https://api.sandbox.pawapay.io" : props.baseUrl();
        this.http = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(base)
                .defaultHeader("Authorization", "Bearer " + (props.apiToken() == null ? "" : props.apiToken()))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ------------------------------------------------------------------ toolkit
    public PawaPayResponse activeConfiguration() { return get("/v2/active-conf"); }

    public PawaPayResponse walletBalances() { return get("/v2/wallet-balances"); }

    // ------------------------------------------------------------------ deposits (collect from the sender)
    public PawaPayResponse initiateDeposit(UUID depositId, String provider, String msisdn, String amount, String currency,
                                           String clientReferenceId, String customerMessage) {
        ObjectNode b = mapper.createObjectNode();
        b.put("depositId", depositId.toString());
        ObjectNode payer = b.putObject("payer");
        payer.put("type", "MMO");
        ObjectNode acc = payer.putObject("accountDetails");
        acc.put("provider", provider);
        acc.put("phoneNumber", msisdn);
        b.put("amount", amount);
        b.put("currency", currency);
        if (clientReferenceId != null) b.put("clientReferenceId", clientReferenceId);
        if (customerMessage != null) b.put("customerMessage", customerMessage);
        return post("/v2/deposits", b);
    }

    public PawaPayResponse checkDeposit(UUID depositId) { return get("/v2/deposits/" + depositId); }

    // ------------------------------------------------------------------ remittances (send to the recipient abroad)
    /** body = the full request (built by CrossBorderTransferService). */
    public PawaPayResponse initiateRemittance(ObjectNode body) { return post("/v2/remittances", body); }

    public PawaPayResponse checkRemittance(UUID remittanceId) { return get("/v2/remittances/" + remittanceId); }

    // ------------------------------------------------------------------ payouts (local, not cross-border)
    public PawaPayResponse checkPayout(UUID payoutId) { return get("/v2/payouts/" + payoutId); }

    // ------------------------------------------------------------------ refunds (give a collected deposit back)
    public PawaPayResponse initiateRefund(UUID refundId, UUID depositId, String amount, String currency) {
        ObjectNode b = mapper.createObjectNode();
        b.put("refundId", refundId.toString());
        b.put("depositId", depositId.toString());
        b.put("amount", amount);
        b.put("currency", currency);
        return post("/v2/refunds", b);
    }

    public PawaPayResponse checkRefund(UUID refundId) { return get("/v2/refunds/" + refundId); }

    // ------------------------------------------------------------------ plumbing
    private PawaPayResponse get(String path) {
        return call(() -> http.get().uri(path).retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class), "GET " + path);
    }

    private PawaPayResponse post(String path, Object body) {
        return call(() -> http.post().uri(path).body(body).retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class), "POST " + path);
    }

    private PawaPayResponse call(java.util.function.Supplier<ResponseEntity<String>> request, String what) {
        ResponseEntity<String> res;
        try {
            res = request.get();
        } catch (Exception e) {
            throw new PawaPayUnavailableException("pawaPay " + what + " did not answer: " + e.getClass().getSimpleName(), e);
        }
        int code = res.getStatusCode().value();
        if (code >= 500) {
            throw new PawaPayUnavailableException("pawaPay " + what + " answered HTTP " + code, null);
        }
        JsonNode json;
        try {
            String text = res.getBody();
            json = text == null || text.isBlank() ? mapper.missingNode() : mapper.readTree(text);
        } catch (Exception e) {
            throw new PawaPayUnavailableException("pawaPay " + what + " sent an unreadable answer", e);
        }
        if (code == 401 || code == 403) {
            log.error("pawaPay {} refused: HTTP {} (check PAWAPAY_API_TOKEN and which environment it belongs to)", what, code);
        }
        return new PawaPayResponse(code, json);
    }
}
