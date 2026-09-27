package com.glr.deenwallet.monime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glr.deenwallet.transaction.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {
    private static final String HEADER = "Monime-Signature";

    private final WebhookSignatureVerifier verifier;
    private final TransactionService tx;
    private final ObjectMapper mapper;
    private final WebhookEventRepository events;

    @Transactional
    @PostMapping("/monime")
    public ResponseEntity<Map<String, Object>> receive(
            HttpServletRequest req,
            @RequestHeader(value = HEADER, required = false) String sig
    ) throws Exception {
        byte[] raw = req.getInputStream().readAllBytes();
        if (!verifier.isValid(raw, sig)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid signature"));
        }

        JsonNode payload = mapper.readTree(raw);
        String name = payload.path("event").path("name").asText(null);
        String resource = payload.path("data").path("id").asText(null);
        if (name == null || resource == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing event name or resource id"));
        }

        String eventId = payload.path("id").asText(null);
        if (eventId == null || eventId.isBlank()) eventId = payload.path("event").path("id").asText(null);
        if (eventId == null || eventId.isBlank()) eventId = sha256(raw);

        String key = eventId + ":" + name;
        if (events.existsByEventKey(key)) {
            return ResponseEntity.ok(Map.of("success", true, "duplicate", true));
        }

        events.save(WebhookEvent.builder()
                .eventKey(key)
                .eventName(name)
                .resourceId(resource)
                .build());

        String reason = extractFailureReason(payload);

        switch (name) {
            case "payment_code.processed" -> tx.handlePaymentCodeProcessed(resource);
            case "payment_code.failed", "payment_code.expired", "payment_code.canceled" ->
                    tx.handlePaymentCodeFailed(resource, reason);
            case "payout.completed" -> tx.handlePayoutCompleted(resource);
            case "payout.failed", "payout.rejected", "payout.canceled" ->
                    tx.handlePayoutFailed(resource, reason);
            default -> log.info("Ignoring unhandled webhook event: {}", name);
        }

        return ResponseEntity.ok(Map.of("success", true));
    }

    private String extractFailureReason(JsonNode payload) {
        String[] paths = {
                "/data/reason",
                "/data/failureReason",
                "/data/failure_reason",
                "/data/error/message",
                "/data/error/reason",
                "/data/statusReason",
                "/data/status_reason",
                "/error/message",
                "/error/reason"
        };
        for (String path : paths) {
            JsonNode node = payload.at(com.fasterxml.jackson.core.JsonPointer.compile(path));
            if (node.isTextual() && !node.asText().isBlank()) return cleanReason(node.asText());
        }
        return null;
    }

    private String cleanReason(String reason) {
        String clean = reason.trim().replaceAll("\\s+", " ");
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b));
        return result.toString();
    }
}
