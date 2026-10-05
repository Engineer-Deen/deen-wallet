package com.glr.deenwallet.maintenance;

import com.glr.deenwallet.auth.RefreshTokenRepository;
import com.glr.deenwallet.monime.WebhookEventRepository;
import com.glr.deenwallet.otp.EmailOtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Nightly cleanup of tables that otherwise grow forever:
 * refresh_tokens (a row per login AND per token refresh), email_otps, webhook_events.
 * (error_logs is handled by ErrorLogCleanupJob.)
 * Each step is independent so one failure never blocks the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataRetentionJob {

    private static final Duration REVOKED_TOKEN_KEEP = Duration.ofDays(1);
    private static final Duration OTP_KEEP = Duration.ofDays(2);
    private static final Duration WEBHOOK_EVENT_KEEP = Duration.ofDays(90);

    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailOtpRepository emailOtpRepository;
    private final WebhookEventRepository webhookEventRepository;

    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        Instant now = Instant.now();
        run("refresh_tokens", () -> refreshTokenRepository.purge(now, now.minus(REVOKED_TOKEN_KEEP)));
        run("email_otps", () -> emailOtpRepository.purgeOlderThan(now.minus(OTP_KEEP)));
        run("webhook_events", () -> webhookEventRepository.purgeOlderThan(now.minus(WEBHOOK_EVENT_KEEP)));
    }

    private void run(String table, java.util.function.IntSupplier step) {
        try {
            int deleted = step.getAsInt();
            log.info("Retention: purged {} rows from {}", deleted, table);
        } catch (Exception e) {
            log.error("Retention: failed to purge {}", table, e);
        }
    }
}
