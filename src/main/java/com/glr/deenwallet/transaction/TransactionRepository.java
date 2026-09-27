package com.glr.deenwallet.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    @Query("SELECT DISTINCT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.createdAt DESC")
    List<Transaction> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);

    Optional<Transaction> findByMonimePaymentCodeId(String monimePaymentCodeId);
    Optional<Transaction> findByMonimePayoutId(String monimePayoutId);
    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, Instant cutoff);

    @Query("SELECT t FROM Transaction t WHERE LOWER(t.transactionCode) = LOWER(:transactionCode)")
    Optional<Transaction> findByTransactionCodeIgnoreCase(@Param("transactionCode") String transactionCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.monimePaymentCodeId = :paymentCodeId")
    Optional<Transaction> findByMonimePaymentCodeIdForUpdate(@Param("paymentCodeId") String paymentCodeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.monimePayoutId = :payoutId")
    Optional<Transaction> findByMonimePayoutIdForUpdate(@Param("payoutId") String payoutId);

    @Query("SELECT DISTINCT t FROM Transaction t ORDER BY t.createdAt DESC")
    List<Transaction> findAllByOrderByCreatedAtDesc();

    @Query("SELECT SUM(t.amountValue) FROM Transaction t")
    Long sumAmountValue();

    @Query("SELECT SUM(t.amountValue) FROM Transaction t WHERE t.status = :status")
    Long sumAmountValueByStatus(@Param("status") TransactionStatus status);

    long countByStatus(TransactionStatus status);
    List<Transaction> findByUserId(UUID userId);
}
