package com.glr.deenwallet.waec;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WaecPinRepository extends JpaRepository<WaecPin, UUID> {

    /**
     * Locks the oldest available pins for this transaction only. SKIP LOCKED means two buyers at
     * the same moment can never receive the same pin: the second simply gets different rows.
     * Must run inside a transaction.
     */
    @Query(value = "SELECT * FROM waec_pins WHERE status = 'AVAILABLE' "
            + "ORDER BY created_at, id LIMIT :n FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<WaecPin> lockAvailable(@Param("n") int n);

    List<WaecPin> findByOrderId(UUID orderId);

    long countByStatus(WaecPinStatus status);

    @Query("SELECT p.pinHash FROM WaecPin p WHERE p.pinHash IN :hashes")
    List<String> existingPinHashes(@Param("hashes") Collection<String> hashes);

    @Query("SELECT p.serialHash FROM WaecPin p WHERE p.serialHash IN :hashes")
    List<String> existingSerialHashes(@Param("hashes") Collection<String> hashes);

    @Query("SELECT p.orderId FROM WaecPin p WHERE p.orderId IS NOT NULL AND (p.pinHash = :hash OR p.serialHash = :hash)")
    List<UUID> orderIdsByPinOrSerialHash(@Param("hash") String hash);

    @Modifying
    @Query("UPDATE WaecPin p SET p.status = com.glr.deenwallet.waec.WaecPinStatus.AVAILABLE, "
            + "p.orderId = NULL, p.reservedAt = NULL "
            + "WHERE p.orderId = :orderId AND p.status = com.glr.deenwallet.waec.WaecPinStatus.RESERVED")
    int releaseByOrder(@Param("orderId") UUID orderId);

    /**
     * Safety net: gives back reserved pins whose purchase is no longer waiting for payment
     * (cancelled, failed, or the transaction row no longer exists).
     */
    @Modifying
    @Query(value = "UPDATE waec_pins p SET status = 'AVAILABLE', order_id = NULL, reserved_at = NULL "
            + "WHERE p.status = 'RESERVED' AND p.reserved_at < :cutoff "
            + "AND NOT EXISTS (SELECT 1 FROM waec_orders o JOIN transactions t ON t.id = o.transaction_id "
            + "WHERE o.id = p.order_id AND t.status IN ('AWAITING_PAYMENT', 'PAID_IN'))", nativeQuery = true)
    int releaseStale(@Param("cutoff") Instant cutoff);
}
