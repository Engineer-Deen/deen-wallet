package com.glr.deenwallet.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Reconciles payouts that remain PAYING_OUT after a grace period.
 * The existing TransactionExpiryScheduler/TransactionService handles stale
 * AWAITING_PAYMENT and PAID_IN transactions, including safe payout resumption.
 */
@Component
@RequiredArgsConstructor
public class PayoutReconciliationScheduler {
    private static final int BATCH_SIZE = 50;

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;

    @Scheduled(fixedRate = 120_000)
    public void reconcileStalePayouts() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.MINUTES);

        transactionRepository.findByStatusAndCreatedAtBefore(
                        TransactionStatus.PAYING_OUT, cutoff, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(Transaction::getMonimePayoutId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(transactionService::reconcilePayout);
    }
}
