package com.glr.deenwallet.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HoneyCoin calls this when an on-ramp changes (set the URL https://api.deenwallapp.com/api/v1/webhooks/honeycoin in their dashboard
 * and put the same secret in HONEYCOIN_WEBHOOK_SECRET). It is only a nudge: the order is always re-read from HoneyCoin before it is
 * marked paid, so a forged call cannot make anything succeed.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class HoneyCoinWebhookController {

    private final CryptoOrderService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public HoneyCoinWebhookController(CryptoOrderService service) {
        this.service = service;
    }

    @PostMapping("/honeycoin")
    public ResponseEntity<Map<String, Object>> receive(@RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
                                                       @RequestBody String raw) {
        JsonNode body;
        try {
            body = mapper.readTree(raw == null ? "" : raw);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unreadable body"));
        }
        try {
            service.handleWebhook(signature, body);
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }
}
