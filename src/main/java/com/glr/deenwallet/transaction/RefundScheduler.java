package com.glr.deenwallet.transaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Pays back money that was collected but could not be delivered. Safe to run repeatedly:
 * the refund uses a fixed idempotency key (refund-{transactionId}), so retries never double-refund.
 * NOTE: runs on every app instance. If you ever run more than one instance, add ShedLock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundScheduler {
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void processPendingRefunds() {
        transactionRepository.findByStatusAndCreatedAtBefore(
                        TransactionStatus.REFUND_PENDING, Instant.now(), PageRequest.of(0, 50))
                .forEach(t -> {
                    try {
                        transactionService.processRefund(t.getId());
                    } catch (Exception e) {
                        log.error("Refund processing error for transaction {}", t.getTransactionCode(), e);
                    }
                });
    }
}
