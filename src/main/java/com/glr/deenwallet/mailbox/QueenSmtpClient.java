package com.glr.deenwallet.mailbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Sends mail through the QueenSMTP HTTPS API (POST /v1/send). The key stays in the server environment
 * (QUEENSMTP_API_KEY) and is never sent to the browser or logged.
 * Every failure becomes a readable IllegalArgumentException (the message is wrong) or IllegalStateException
 * (the service is not ready); the admin app shows the text as it is.
 */
@Component
public class QueenSmtpClient {

    private static final Set<String> KEY_PROBLEMS = Set.of("api_key_missing", "invalid_api_key", "api_key_ip_restricted", "api_key_domain_restricted");
    private static final Set<String> DOMAIN_PROBLEMS = Set.of("domain_not_verified", "domain_not_active", "dns_check_failed", "domain_suspended",
            "sending_paused", "account_suspended", "EMAIL_NOT_VERIFIED", "private_domains_blocked");
    private static final Set<String> BUSY = Set.of("daily_limit_reached", "rate_limited", "review_hold", "scan_unavailable", "internal_error");

    @Value("${QUEENSMTP_API_KEY:}")
    private String apiKey;

    @Value("${QUEENSMTP_BASE_URL:https://queensmtp.com/v1}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Sent(String id, String status) { }

    /**
     * The request may or may not have reached QueenSMTP (connection dropped, timeout, unreadable answer).
     * The caller must NOT send the same email again on its own.
     */
    public static class Uncertain extends RuntimeException {
        public Uncertain() {
            super("It is not known whether the email was sent.");
        }
    }

    public record Status(String status, String error) { }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** One recipient per call (QueenSMTP rule). inReplyTo is optional and only used when it looks like a real Message-ID. */
    public Sent send(String from, String fromName, String replyTo, String to, String subject, String html, String text, String inReplyTo) {
        if (!configured()) throw new IllegalStateException("Email sending is not connected yet. Add QUEENSMTP_API_KEY to the server settings.");
        ObjectNode body = mapper.createObjectNode();
        body.put("from", from);
        if (fromName != null && !fromName.isBlank()) body.put("fromName", fromName);
        if (replyTo != null && !replyTo.isBlank()) body.put("replyTo", replyTo);
        body.put("to", to);
        body.put("subject", subject);
        body.put("html", html);
        body.put("text", text);
        if (inReplyTo != null && inReplyTo.matches("^<[^<>\\s]{1,300}>$")) {
            ObjectNode headers = body.putObject("headers");
            headers.put("In-Reply-To", inReplyTo);
            headers.put("References", inReplyTo);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/send"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        JsonNode json;
        int code;
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            code = res.statusCode();
            json = parse(res.body());
        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
            // never connected: nothing was sent
            throw new IllegalStateException("The email service could not be reached. Please try again.");
        } catch (IOException e) {
            throw new Uncertain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Uncertain();
        }
        if (code >= 200 && code < 300) {
            if (!json.path("success").asBoolean(false)) throw new Uncertain();
            JsonNode data = json.path("data");
            return new Sent(data.path("id").asText(""), data.path("status").asText("queued"));
        }
        String errCode = json.path("code").asText("");
        // a 5xx without QueenSMTP's own error code (gateway timeout, proxy page) says nothing about whether it was accepted
        if (code >= 500 && errCode.isEmpty()) throw new Uncertain();
        throw failure(errCode, json.path("error").asText(""));
    }

    /** queued | sent | failed, or null when it cannot be read right now. */
    public Status status(String messageId) {
        if (!configured() || messageId == null || messageId.isBlank()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/messages/" + URLEncoder.encode(messageId, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) return null;
            JsonNode data = parse(res.body()).path("data");
            String s = data.path("status").asText("");
            if (s.isEmpty()) return null;
            JsonNode err = data.path("error");
            return new Status(s, err.isNull() || err.isMissingNode() ? "" : err.asText(""));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    private RuntimeException failure(String code, String prose) {
        if ("recipient_blocked".equals(code)) {
            return new IllegalArgumentException("This address cannot receive mail from us: an earlier email to it bounced or was refused.");
        }
        if ("invalid_recipient".equals(code) || "invalid_request".equals(code)) {
            return new IllegalArgumentException("The email address or the message is not valid. Please check them.");
        }
        if ("message_too_large".equals(code)) return new IllegalArgumentException("The message is too long.");
        if ("insufficient_credits".equals(code)) {
            return new IllegalStateException("The email service is out of credits. Top up QueenSMTP, then try again.");
        }
        if (KEY_PROBLEMS.contains(code)) {
            return new IllegalStateException("The email service did not accept the server's key. Check QUEENSMTP_API_KEY.");
        }
        if (DOMAIN_PROBLEMS.contains(code)) {
            return new IllegalStateException("The sending domain is not ready at QueenSMTP (" + code + "). Check the QueenSMTP dashboard.");
        }
        if (BUSY.contains(code)) return new IllegalStateException("The email service is busy right now. Please try again in a few minutes.");
        if (code.startsWith("spam_") || code.startsWith("content_") || "prefilter_rejected".equals(code) || "virus_detected".equals(code)
                || "identity_mismatch".equals(code) || "link_blocklisted".equals(code) || "domain_blocklisted".equals(code)
                || "UNSUBSCRIBE_HEADER_REQUIRED".equals(code) || "test_guard".equals(code)) {
            return new IllegalArgumentException("QueenSMTP refused this message as spam-like or unsafe (" + code + "). Reword it and try again.");
        }
        return new IllegalStateException("The email could not be sent right now" + (code.isEmpty() ? "." : " (" + code + ")."));
    }
}
