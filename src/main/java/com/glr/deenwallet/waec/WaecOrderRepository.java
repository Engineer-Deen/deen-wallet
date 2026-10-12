package com.glr.deenwallet.waec;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaecOrderRepository extends JpaRepository<WaecOrder, UUID> {

    Optional<WaecOrder> findByTransactionId(UUID transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM WaecOrder o WHERE o.transactionId = :transactionId")
    Optional<WaecOrder> findByTransactionIdForUpdate(@Param("transactionId") UUID transactionId);

    long countByFulfilledAtIsNotNull();

    long countByFulfilledAtIsNotNullAndDeliveryStatus(WaecDeliveryStatus status);

    /**
     * Purchased-pin list for the admin tab. q is already lower-cased and wrapped in %...%
     * (use "%" for no filter). pinOrderIds is never empty (the caller passes a dummy id) so the
     * IN clause is always valid.
     */
    @Query("SELECT o FROM WaecOrder o LEFT JOIN User u ON u.id = o.userId "
            + "WHERE o.fulfilledAt IS NOT NULL "
            + "AND o.deliveryStatus IN :statuses "
            + "AND (LOWER(o.transactionCode) LIKE :q OR LOWER(COALESCE(o.deliveryEmail, '')) LIKE :q "
            + "OR LOWER(COALESCE(u.email, '')) LIKE :q OR LOWER(COALESCE(u.fullName, '')) LIKE :q "
            + "OR LOWER(COALESCE(u.accountNumber, '')) LIKE :q OR o.id IN :pinOrderIds) "
            + "ORDER BY o.fulfilledAt DESC")
    List<WaecOrder> search(@Param("q") String q,
                           @Param("statuses") Collection<WaecDeliveryStatus> statuses,
                           @Param("pinOrderIds") Collection<UUID> pinOrderIds,
                           Pageable pageable);

    @Query("SELECT o FROM WaecOrder o WHERE o.fulfilledAt IS NOT NULL "
            + "AND o.deliveryStatus IN :statuses AND o.deliveryAttempts < :maxAttempts "
            + "AND o.updatedAt < :before ORDER BY o.updatedAt")
    List<WaecOrder> findDeliveryRetryCandidates(@Param("statuses") Collection<WaecDeliveryStatus> statuses,
                                                @Param("maxAttempts") int maxAttempts,
                                                @Param("before") Instant before,
                                                Pageable pageable);
}
