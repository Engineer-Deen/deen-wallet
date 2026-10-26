package com.glr.deenwallet.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the audit log complete: copies WAEC / payout-recovery lines in, and removes very old lines. */
@Component
public class AuditMirrorJob {

    private static final Logger log = LoggerFactory.getLogger(AuditMirrorJob.class);

    private final AuditService audit;

    @Value("${app.audit.retention-days:365}")
    private int retentionDays;

    public AuditMirrorJob(AuditService audit) {
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void run() {
        try {
            audit.mirror();
        } catch (Exception e) {
            log.warn("Audit mirror failed: {}", e.toString());
        }
        try {
            audit.purge(retentionDays);
        } catch (Exception e) {
            log.warn("Audit purge failed: {}", e.toString());
        }
    }
}
