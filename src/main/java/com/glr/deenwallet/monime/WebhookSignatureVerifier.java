package com.glr.deenwallet.monime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookSignatureVerifier {

    private static final long TOLERANCE_SECONDS = 300L;

    private final MonimeProperties monimeProperties;

    public boolean isValid(byte[] rawBodyBytes, String signatureHeader) {

        if (rawBodyBytes == null || signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Monime webhook signature is missing");
            return false;
        }

        Map<String, String> parts = parseHeader(signatureHeader);

        String timestamp = parts.get("t");
        String receivedSignature = parts.get("v1");

        if (timestamp == null || receivedSignature == null
                || timestamp.isBlank() || receivedSignature.isBlank()) {
            log.warn("Monime webhook signature header is malformed");
            return false;
        }

        if (!isTimestampFresh(timestamp)) {
            log.warn("Monime webhook timestamp is outside the 5-minute freshness window");
            return false;
        }

        String secret = monimeProperties.webhookSecret();

        if (secret == null || secret.isBlank()) {
            log.error("Monime webhook secret is not configured");
            return false;
        }

        /*
         * Monime signs:
         *
         *     timestamp + "_" + exact raw request body
         *
         * using HMAC-SHA256, then Base64-encodes the result.
         */
        byte[] signedPayload = buildSignedPayload(timestamp, rawBodyBytes);

        String expectedSignature = computeHmac(signedPayload, secret);

        try {
            byte[] expectedBytes = Base64.getDecoder().decode(expectedSignature);
            byte[] receivedBytes = Base64.getDecoder().decode(receivedSignature);

            boolean matches = MessageDigest.isEqual(
                    expectedBytes,
                    receivedBytes
            );

            if (!matches) {
                log.warn(
                        "Monime webhook signature mismatch. bodyBytes={}, timestamp={}",
                        rawBodyBytes.length,
                        timestamp
                );
            }

            return matches;

        } catch (IllegalArgumentException e) {
            log.warn("Monime webhook signature is not valid Base64");
            return false;
        }
    }

    private byte[] buildSignedPayload(
            String timestamp,
            byte[] rawBodyBytes
    ) {
        byte[] timestampBytes =
                timestamp.getBytes(StandardCharsets.US_ASCII);

        byte[] separator = "_".getBytes(StandardCharsets.US_ASCII);

        byte[] result = new byte[
                timestampBytes.length
                        + separator.length
                        + rawBodyBytes.length
                ];

        System.arraycopy(
                timestampBytes,
                0,
                result,
                0,
                timestampBytes.length
        );

        System.arraycopy(
                separator,
                0,
                result,
                timestampBytes.length,
                separator.length
        );

        System.arraycopy(
                rawBodyBytes,
                0,
                result,
                timestampBytes.length + separator.length,
                rawBodyBytes.length
        );

        return result;
    }

    private Map<String, String> parseHeader(String header) {

        Map<String, String> result = new HashMap<>();

        for (String part : header.split(",")) {

            String[] keyValue = part.split("=", 2);

            if (keyValue.length != 2) {
                continue;
            }

            String key = keyValue[0].trim();
            String value = keyValue[1].trim();

            if (!key.isEmpty() && !value.isEmpty()) {
                result.put(key, value);
            }
        }

        return result;
    }

    private boolean isTimestampFresh(String timestamp) {

        try {
            long webhookTime = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();

            return Math.abs(now - webhookTime) <= TOLERANCE_SECONDS;

        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String computeHmac(byte[] payload, String secret) {

        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(
                    new SecretKeySpec(
                            secret.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"
                    )
            );

            byte[] digest = mac.doFinal(payload);

            return Base64.getEncoder().encodeToString(digest);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to compute Monime webhook signature",
                    e
            );
        }
    }
}