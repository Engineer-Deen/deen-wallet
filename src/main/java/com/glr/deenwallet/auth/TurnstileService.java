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
 * refused unless TURNSTILE_FAIL_OPEN=true is set (use that only while Cloudflare itself is down); a wrong or missing check is refused.
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

    /** Default false: if Cloudflare cannot be asked, the login is refused. Set true only during a real Cloudflare outage. */
    @Value("${TURNSTILE_FAIL_OPEN:false}")
    private boolean failOpen;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public boolean isEnabled() {
        return siteKey != null && !siteKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    public String getSiteKey() {
        return isEnabled() ? siteKey.trim() : "";
    }

    /**
     * Throws IllegalArgumentException (shown to the person) when the check is missing, refused, or could not be run.
     * Cloudflare is also told the visitor's IP (it uses it as an extra signal). A token can be used only once:
     * Cloudflare itself refuses a token that was already checked.
     */
    public void verify(String token) {
        if (!isEnabled()) return;
        if (token == null || token.isBlank()) {
            com.glr.deenwallet.monitoring.SecurityTag.tag("HUMAN_FAILED");
            throw new IllegalArgumentException("Please tick the \"Verify you are human\" box first.");
        }
        String t = token.trim();
        if (t.length() > 2048) { // Cloudflare tokens are never longer; do not even forward junk
            com.glr.deenwallet.monitoring.SecurityTag.tag("HUMAN_FAILED");
            throw new IllegalArgumentException("The human check did not pass. Please try again.");
        }
        String form = "secret=" + URLEncoder.encode(secretKey.trim(), StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(t, StandardCharsets.UTF_8);
        String ip = clientIp();
        if (ip != null) form += "&remoteip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8);

        String answer = null;
        for (int attempt = 1; attempt <= 2 && answer == null; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(verifyUrl))
                        .timeout(Duration.ofSeconds(6))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) {
                    answer = res.body() == null ? "" : res.body();
                } else {
                    log.warn("Turnstile verify answered HTTP {} (attempt {})", res.statusCode(), attempt);
                }
            } catch (IOException e) {
                log.warn("Turnstile verify unreachable ({}) (attempt {})", e.toString(), attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Turnstile verify interrupted");
                break;
            }
        }

        if (answer == null) {
            // Cloudflare could not be asked. Refused by default, so a bot cannot get in by making the check fail.
            // Only if you set TURNSTILE_FAIL_OPEN=true (for a real Cloudflare outage) is the login let through.
            if (failOpen) {
                log.warn("Turnstile could not be checked - TURNSTILE_FAIL_OPEN=true, letting the login through");
                return;
            }
            throw new IllegalArgumentException("We could not run the human check just now. Please try again in a moment.");
        }
        if (!SUCCESS.matcher(answer).find()) {
            com.glr.deenwallet.monitoring.SecurityTag.tag("HUMAN_FAILED");
            throw new IllegalArgumentException("The human check did not pass. Please try again.");
        }
    }

    /** The real visitor IP (nginx passes it on and Tomcat already puts it in remoteAddr), or null outside a request. */
    private static String clientIp() {
        try {
            if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    instanceof org.springframework.web.context.request.ServletRequestAttributes a) {
                String ip = a.getRequest().getRemoteAddr();
                return ip == null || ip.isBlank() ? null : ip.trim();
            }
        } catch (RuntimeException ignored) {
            // the IP is only an extra hint for Cloudflare
        }
        return null;
    }
}
