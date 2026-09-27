package com.glr.deenwallet.transaction;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every minute and marks any transaction that's been sitting in
 * AWAITING_PAYMENT past the payment code's ~10-minute window as
 * FAILED. Without this, a transaction where the sender never paid
 * would stay AWAITING_PAYMENT forever.
 */
@Component
@RequiredArgsConstructor
public class TransactionExpiryScheduler {

    private final TransactionService transactionService;

    @Scheduled(fixedRate = 60_000)
    public void expireStaleTransactions() {
        transactionService.expireStalePendingTransactions();
    }
}
