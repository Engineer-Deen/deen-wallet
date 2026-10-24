package com.glr.deenwallet.maintenance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Keeps the daily_stats table (completed transactions, volume and DeenWallet fees per day, UTC) up to date,
 * so the admin Home numbers survive RecordRetentionJob deleting old transactions.
 *
 * Runs at 03:30 every night (before the 03:45 wipe) and once when the app starts.
 * It recomputes the last 7 days each time; when the table is empty it backfills everything that still exists.
 */
@Component
public class DailyStatsJob {

    private static final Logger log = LoggerFactory.getLogger(DailyStatsJob.class);
    private static final int RECOMPUTE_DAYS = 7;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;

    public DailyStatsJob(TransactionTemplate tx) {
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        snapshot();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void snapshot() {
        try {
            Integer rows = tx.execute(status -> {
                Number existing = (Number) em.createNativeQuery("SELECT COUNT(*) FROM daily_stats").getSingleResult();
                Instant from = existing.longValue() == 0
                        ? Instant.EPOCH
                        : LocalDate.now(ZoneOffset.UTC).minusDays(RECOMPUTE_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC);
                return em.createNativeQuery(
                                "INSERT INTO daily_stats (day, tx_count, volume_value, fees_value, updated_at) "
                                        + "SELECT (x.created_at AT TIME ZONE 'UTC')::date, COUNT(*), "
                                        + "COALESCE(SUM(x.amount_value), 0), COALESCE(SUM(x.deen_wallet_fee_value), 0), now() "
                                        + "FROM (SELECT created_at, amount_value, deen_wallet_fee_value FROM transactions "
                                        + "      WHERE status = 'COMPLETED' AND created_at >= :from "
                                        + "      UNION ALL "
                                        + "      SELECT created_at, amount_value, deen_wallet_fee_value FROM transactions_archive "
                                        + "      WHERE status = 'COMPLETED' AND created_at >= :from) x "
                                        + "GROUP BY 1 "
                                        + "ON CONFLICT (day) DO UPDATE SET tx_count = EXCLUDED.tx_count, "
                                        + "volume_value = EXCLUDED.volume_value, fees_value = EXCLUDED.fees_value, updated_at = now()")
                        .setParameter("from", from)
                        .executeUpdate();
            });
            log.info("Daily stats: {} day rows written", rows);
        } catch (Exception e) {
            log.error("Daily stats snapshot failed", e);
        }
    }
}
