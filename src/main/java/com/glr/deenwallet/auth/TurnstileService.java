package com.glr.deenwallet.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Cloudflare Turnstile ("Verify you are human") for the customer and admin password logins.
 *
 * Switched on only when BOTH TURNSTILE_SITE_KEY and TURNSTILE_SECRET_KEY are set in the environment file;
 * with either one empty nothing changes for anybody. If Cloudflare itself cannot be reached the login is
 * allowed (a Cloudflare outage must never lock every customer out); a wrong or missing check is refused.
 */
@Slf4j
@Service
public class TurnstileService {

    private static final Pattern SUCCESS = Pattern.compile("\"success\"\\s*:\\s*true");

    @Value("${TURNSTILE_SITE_KEY:}")
    private String siteKey;

    @Value("${TURNSTILE_SECRET_KEY:}")
    private String secretKey;

    @Value("${TURNSTILE_VERIFY_URL:https://challenges.cloudflare.com/turnstile/v0/siteverify}")
    private String verifyUrl;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public boolean isEnabled() {
        return siteKey != null && !siteKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    public String getSiteKey() {
        return isEnabled() ? siteKey.trim() : "";
    }

    /** Throws IllegalArgumentException (shown to the person) when the check is missing or failed. */
    public void verify(String token) {
        if (!isEnabled()) return;
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Please tick the \"Verify you are human\" box first.");
        }
        String form = "secret=" + URLEncoder.encode(secretKey.trim(), StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(verifyUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Turnstile verify answered HTTP {} - letting the login through", res.statusCode());
                return;
            }
            if (!SUCCESS.matcher(res.body() == null ? "" : res.body()).find()) {
                throw new IllegalArgumentException("The human check did not pass. Please try again.");
            }
        } catch (IOException e) {
            log.warn("Turnstile verify unreachable ({}) - letting the login through", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Turnstile verify interrupted - letting the login through");
        }
    }
}
