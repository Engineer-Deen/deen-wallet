package com.glr.deenwallet.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    /** Newest-first, bounded. Uses idx_transactions_user_id_created_at (no sort, no DISTINCT). */
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.createdAt DESC")
    List<Transaction> findRecentByUserId(@Param("userId") UUID userId, Pageable pageable);

    /** Unbounded - kept only for callers that truly need everything. Do not use on request paths. */
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.createdAt DESC")
    List<Transaction> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);

    Optional<Transaction> findByMonimePaymentCodeId(String monimePaymentCodeId);
    Optional<Transaction> findByMonimePayoutId(String monimePayoutId);
    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, Instant cutoff);

    @Query("SELECT t FROM Transaction t WHERE t.status = :status AND t.createdAt < :cutoff ORDER BY t.createdAt")
    List<Transaction> findByStatusAndCreatedAtBefore(
            @Param("status") TransactionStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    // Codes are stored upper-case. Upper-casing the PARAMETER (not the column) keeps the
    // unique index usable; LOWER(column) forced a full table scan (126 ms at 200k rows).
    @Query("SELECT t FROM Transaction t WHERE t.transactionCode = UPPER(:transactionCode)")
    Optional<Transaction> findByTransactionCodeIgnoreCase(@Param("transactionCode") String transactionCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.monimePaymentCodeId = :paymentCodeId")
    Optional<Transaction> findByMonimePaymentCodeIdForUpdate(@Param("paymentCodeId") String paymentCodeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.monimePayoutId = :payoutId")
    Optional<Transaction> findByMonimePayoutIdForUpdate(@Param("payoutId") String payoutId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

    /** Failed-payout queue: bounded, newest failure first. */
    @Query("SELECT t FROM Transaction t WHERE t.status IN :statuses ORDER BY COALESCE(t.payoutFailedAt, t.updatedAt) DESC")
    List<Transaction> findQueue(@Param("statuses") List<TransactionStatus> statuses, Pageable pageable);

    long countByUserIdAndStatus(UUID userId, TransactionStatus status);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.userId = :userId AND t.createdAt > :since")
    long countByUserIdSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(t.amountValue), 0) FROM Transaction t WHERE t.userId = :userId "
            + "AND t.createdAt > :since AND t.status NOT IN (com.glr.deenwallet.transaction.TransactionStatus.CANCELED)")
    long sumAmountByUserIdSince(@Param("userId") UUID userId, @Param("since") Instant since);

    /** Double-tap protection: the same still-unpaid request created moments ago. */
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.status = com.glr.deenwallet.transaction.TransactionStatus.AWAITING_PAYMENT "
            + "AND t.createdAt > :since AND t.amountValue = :amount AND t.sourcePhone = :sourcePhone "
            + "AND t.serviceType = :serviceType AND COALESCE(t.destinationPhone, '') = :destPhone "
            + "AND COALESCE(t.destinationBankAccountNumber, '') = :bankAcct "
            + "ORDER BY t.createdAt DESC")
    List<Transaction> findRecentDuplicates(@Param("userId") UUID userId, @Param("since") Instant since,
                                           @Param("amount") Long amount, @Param("sourcePhone") String sourcePhone,
                                           @Param("serviceType") TransactionServiceType serviceType,
                                           @Param("destPhone") String destPhone, @Param("bankAcct") String bankAcct);

    /** Admin list, bounded + paged. Pass PageRequest.of(page, size, Sort.by(DESC, "createdAt")). */
    List<Transaction> findAllBy(Pageable pageable);

    /** Unbounded - do not use on request paths (kept for compatibility). */
    @Query("SELECT t FROM Transaction t ORDER BY t.createdAt DESC")
    List<Transaction> findAllByOrderByCreatedAtDesc();

    @Query("SELECT SUM(t.amountValue) FROM Transaction t")
    Long sumAmountValue();

    @Query("SELECT SUM(t.amountValue) FROM Transaction t WHERE t.status = :status")
    Long sumAmountValueByStatus(@Param("status") TransactionStatus status);

    long countByStatus(TransactionStatus status);
    List<Transaction> findByUserId(UUID userId);

    /**
     * Candidates for archiving: finished (final-state) transactions older than the
     * retention cutoff. Batched with Pageable so one run never locks/loads the whole
     * table - see TransactionArchiveJob.
     */
    @Query("SELECT t FROM Transaction t WHERE t.status IN :finalStatuses AND t.createdAt < :cutoff ORDER BY t.createdAt")
    List<Transaction> findArchivable(
            @Param("finalStatuses") List<TransactionStatus> finalStatuses,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

}
