package com.glr.deenwallet.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayoutRecoveryActionRepository extends JpaRepository<PayoutRecoveryAction, UUID> {
    List<PayoutRecoveryAction> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
}
