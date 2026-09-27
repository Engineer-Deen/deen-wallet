package com.glr.deenwallet.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Runs once a day and deletes any error log older than 30 days, so
 * storage never grows unbounded. Requires @EnableScheduling on the
 * main application class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorLogCleanupJob {

    private final ErrorLogRepository errorLogRepository;

    // Runs daily at 03:00 server time, a quiet hour for traffic.
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeOldLogs() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        errorLogRepository.deleteByCreatedAtBefore(cutoff);
        log.info("Purged error logs older than {}", cutoff);
    }
}

