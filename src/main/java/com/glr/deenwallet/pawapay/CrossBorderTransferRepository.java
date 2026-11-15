package com.glr.deenwallet.pawapay;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrossBorderTransferRepository extends JpaRepository<CrossBorderTransfer, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM CrossBorderTransfer t WHERE t.id = :id")
    Optional<CrossBorderTransfer> findByIdForUpdate(@Param("id") UUID id);

    Optional<CrossBorderTransfer> findByDepositId(UUID depositId);

    Optional<CrossBorderTransfer> findByRemittanceId(UUID remittanceId);

    Optional<CrossBorderTransfer> findByRefundId(UUID refundId);

    @Query("SELECT t FROM CrossBorderTransfer t WHERE t.status IN :open AND t.updatedAt < :before ORDER BY t.updatedAt")
    List<CrossBorderTransfer> findOpenOlderThan(@Param("open") List<CrossBorderStatus> open, @Param("before") Instant before, Pageable page);

    List<CrossBorderTransfer> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);

    List<CrossBorderTransfer> findAllByOrderByCreatedAtDesc(Pageable page);

    Optional<CrossBorderTransfer> findByUserIdAndClientRequestId(UUID userId, UUID clientRequestId);

    long countByUserIdAndStatusIn(UUID userId, java.util.Collection<CrossBorderStatus> statuses);
}
