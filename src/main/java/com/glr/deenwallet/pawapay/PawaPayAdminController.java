package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only tools to try pawaPay in the SANDBOX with its test phone numbers. /api/admin/** is already limited to
 * ADMIN / SUPER_ADMIN. The test transfer is refused on the live pawaPay address.
 */
@RestController
@RequestMapping("/api/admin/pawapay")
@RequiredArgsConstructor
public class PawaPayAdminController {

    private static final UUID TEST_USER = new UUID(0L, 0L);

    private final PawaPayProperties props;
    private final PawaPayClient client;
    private final CrossBorderTransferService service;
    // Created here on purpose: this app has no ObjectMapper bean to inject.
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("sandbox", props.sandbox());
        m.put("baseUrl", props.baseUrl());
        m.put("tokenSet", props.apiToken() != null && !props.apiToken().isBlank());
        m.put("callbackSecretSet", props.callbackSecret() != null && !props.callbackSecret().isBlank());
        if (!props.enabled()) return ResponseEntity.ok(m);
        try {
            m.put("activeConfiguration", mapper.convertValue(client.activeConfiguration().json(), Object.class));
            m.put("walletBalances", mapper.convertValue(client.walletBalances().json(), Object.class));
        } catch (PawaPayUnavailableException e) {
            m.put("error", "pawaPay could not be reached.");
        }
        return ResponseEntity.ok(m);
    }

    /**
     * Body (all optional except the numbers): payerProvider (default ORANGE_SLE), payerPhone, destProvider, destCountry,
     * recipientPhone, sendAmount, sendCurrency, receiveAmount, receiveCurrency, fxRate. Use pawaPay's sandbox test numbers.
     */
    @PostMapping("/test-transfer")
    public ResponseEntity<?> testTransfer(@RequestBody(required = false) String raw) {
        if (!props.enabled() || !props.sandbox()) {
            return ResponseEntity.status(403).body(Map.of("message", "Test transfers only run on the pawaPay sandbox."));
        }
        try {
            JsonNode b = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            ObjectNode sender = mapper.createObjectNode();
            sender.put("firstName", "Test");
            sender.put("lastName", "Sender");
            sender.put("nationality", "SLE");
            sender.put("phoneNumber", text(b, "payerPhone", ""));
            ObjectNode addr = sender.putObject("address");
            addr.put("addressLine", "1 Test Street");
            addr.put("postalCode", "00000");
            addr.put("city", "Freetown");
            addr.put("country", "SLE");
            ObjectNode id = sender.putObject("identification");
            id.put("type", "PASSPORT");
            id.put("number", "TEST1234");

            CrossBorderTransferService.StartCommand c = new CrossBorderTransferService.StartCommand(
                    TEST_USER,
                    text(b, "payerProvider", "ORANGE_SLE"), text(b, "payerPhone", ""),
                    new BigDecimal(text(b, "sendAmount", "10")), text(b, "sendCurrency", "SLE"), BigDecimal.ZERO,
                    new BigDecimal(text(b, "receiveAmount", "10")), text(b, "receiveCurrency", "GHS"),
                    new BigDecimal(text(b, "fxRate", "1")),
                    text(b, "destCountry", "GHA"), text(b, "destProvider", "MTN_MOMO_GHA"), text(b, "recipientPhone", ""),
                    "Test", "Recipient", sender,
                    text(b, "purposeOfFunds", "FAMILY_SUPPORT"), text(b, "sourceOfFunds", "SALARY"));
            return ResponseEntity.ok(service.view(service.start(c)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "The request body is not valid JSON."));
        } catch (PawaPayUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of("message", "pawaPay could not be reached."));
        }
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<?> transfer(@PathVariable UUID id) {
        return service.find(id)
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(service.view(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Re-checks one transfer right now (same thing the 30-second job does). */
    @PostMapping("/transfers/{id}/check")
    public ResponseEntity<?> check(@PathVariable UUID id) {
        if (!props.enabled()) return ResponseEntity.status(403).body(Map.of("message", "pawaPay is off."));
        try {
            return ResponseEntity.ok(service.view(service.advance(id)));
        } catch (PawaPayUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of("message", "pawaPay could not be reached."));
        }
    }

    private static String text(JsonNode b, String f, String d) {
        JsonNode n = b == null ? null : b.get(f);
        return n == null || n.isNull() || n.asText().isBlank() ? d : n.asText().trim();
    }
}
