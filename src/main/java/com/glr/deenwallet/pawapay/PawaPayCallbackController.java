package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * pawaPay callbacks. Set these URLs in the pawaPay dashboard (one per type):
 *   https://api.deenwallapp.com/api/v1/webhooks/pawapay/{PAWAPAY_CALLBACK_SECRET}/deposits
 *   .../remittances   .../refunds
 *
 * A callback is only a nudge: it is never trusted for the money result. The service reads the real status from
 * pawaPay. A wrong secret (or no secret configured) looks like a page that does not exist.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/pawapay")
@RequiredArgsConstructor
public class PawaPayCallbackController {

    private static final int MAX_BODY = 200_000;
    private static final Set<String> KINDS = Set.of("deposits", "remittances", "refunds", "payouts");

    private final PawaPayProperties props;
    private final CrossBorderTransferService service;
    // Created here on purpose: this app has no ObjectMapper bean to inject.
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/{secret}/{kind}")
    public ResponseEntity<Void> receive(@PathVariable String secret,
                                        @PathVariable String kind,
                                        @RequestBody(required = false) String body) {
        String expected = props.callbackSecret();
        if (!props.enabled() || expected == null || expected.isBlank() || !sameSecret(expected, secret) || !KINDS.contains(kind)) {
            return ResponseEntity.notFound().build();
        }
        if (body == null || body.isBlank() || body.length() > MAX_BODY) {
            return ResponseEntity.badRequest().build();
        }
        if ("payouts".equals(kind)) {
            return ResponseEntity.ok().build();   // we do not use plain payouts
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            String idField = switch (kind) {
                case "deposits" -> "depositId";
                case "remittances" -> "remittanceId";
                default -> "refundId";
            };
            String id = firstText(root.path(idField), data.path(idField));
            if (id == null) {
                log.warn("pawaPay {} callback without {}", kind, idField);
                return ResponseEntity.ok().build();   // nothing we can do with it; do not make pawaPay retry forever
            }
            UUID resourceId;
            try {
                resourceId = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.ok().build();
            }
            String claimed = firstText(root.path("status"), data.path("status"));
            service.onCallback(kind, resourceId, claimed == null ? "" : claimed, sha256(kind + ":" + body));
            return ResponseEntity.ok().build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("pawaPay {} callback could not be processed: {}", kind, e.toString());
            return ResponseEntity.status(500).build();   // pawaPay will send it again
        }
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && n.isTextual() && !n.asText().isBlank()) return n.asText();
        }
        return null;
    }

    private static boolean sameSecret(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
