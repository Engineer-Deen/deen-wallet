package com.glr.deenwallet.maintenance;

import com.glr.deenwallet.transaction.Transaction;
import com.glr.deenwallet.transaction.TransactionArchive;
import com.glr.deenwallet.transaction.TransactionArchiveRepository;
import com.glr.deenwallet.transaction.TransactionRepository;
import com.glr.deenwallet.transaction.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Moves old, finished transactions out of the hot `transactions` table into
 * `transactions_archive`. This is what makes a user's transaction history "not stay
 * forever" without ever deleting the underlying financial record - the row still
 * exists, just not in the table every login/history/dashboard query scans.
 *
 * WHY NOT DELETE OUTRIGHT: financial transaction records are typically subject to
 * multi-year retention requirements (AML/audit) in most jurisdictions, Sierra Leone
 * included. Confirm the exact required retention period with your compliance/legal
 * advisor before ever adding code that permanently deletes rows from the archive.
 * This job never does that - it only moves rows, never destroys them.
 *
 * Tunable via .env, no code change needed:
 *   TRANSACTION_ARCHIVE_AFTER_DAYS (default 180 = ~6 months)   how old + finished
 *     before a transaction leaves the hot table and a user's visible history.
 *   TRANSACTION_ARCHIVE_ENABLED (default true)                 kill switch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionArchiveJob {

    private static final List<TransactionStatus> FINAL_STATUSES =
            List.of(TransactionStatus.COMPLETED, TransactionStatus.FAILED, TransactionStatus.CANCELED);
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 200; // caps one run at 100k rows

    private final TransactionRepository transactionRepository;
    private final TransactionArchiveRepository transactionArchiveRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.transaction-archive.after-days:180}")
    private int archiveAfterDays;

    @Value("${app.transaction-archive.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "0 15 3 * * *")
    public void archiveOldTransactions() {
        if (!enabled) {
            log.info("Transaction archiving is disabled (app.transaction-archive.enabled=false)");
            return;
        }
        Instant cutoff = Instant.now().minus(archiveAfterDays, ChronoUnit.DAYS);
        int totalArchived = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int archivedThisBatch = archiveOneBatch(cutoff);
            totalArchived += archivedThisBatch;
            if (archivedThisBatch < BATCH_SIZE) {
                break; // fewer than a full batch = nothing left to archive
            }
        }
        log.info("Transaction archiving: moved {} transactions older than {} days to transactions_archive",
                totalArchived, archiveAfterDays);
    }

    /** One small transaction per batch, so this never holds a long lock over thousands of rows at once. */
    private int archiveOneBatch(Instant cutoff) {
        return transactionTemplate.execute(status -> {
            List<Transaction> batch = transactionRepository.findArchivable(
                    FINAL_STATUSES, cutoff, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                return 0;
            }
            List<TransactionArchive> archived = batch.stream().map(TransactionArchive::from).toList();
            transactionArchiveRepository.saveAll(archived);
            transactionRepository.deleteAllInBatch(batch);
            return batch.size();
        });
    }
}
