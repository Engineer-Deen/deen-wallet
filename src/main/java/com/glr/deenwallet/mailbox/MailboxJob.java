package com.glr.deenwallet.mailbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks the support inbox every minute and removes very old mail once a day. */
@Component
public class MailboxJob {

    private static final Logger log = LoggerFactory.getLogger(MailboxJob.class);

    @Value("${app.mailbox.retention-days:365}")
    private int retentionDays;

    private final MailboxImap imap;
    private final MailboxService service;

    public MailboxJob(MailboxImap imap, MailboxService service) {
        this.imap = imap;
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void poll() {
        try {
            imap.poll();
        } catch (RuntimeException e) {
            log.warn("Mailbox poll failed: {}", e.toString());
        }
    }

    /** Moves "Sending" to "Sent" (or "Failed") without anybody having to open the message. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void settle() {
        try {
            service.settleStale();
            service.refreshQueued(20);
        } catch (RuntimeException e) {
            log.warn("Mailbox status refresh failed: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        try {
            service.purge(retentionDays);
        } catch (RuntimeException e) {
            log.warn("Mailbox purge failed: {}", e.toString());
        }
    }
}
