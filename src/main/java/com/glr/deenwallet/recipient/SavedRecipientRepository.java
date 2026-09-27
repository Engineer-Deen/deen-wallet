package com.glr.deenwallet.recipient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedRecipientRepository extends JpaRepository<SavedRecipient, UUID> {

    List<SavedRecipient> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<SavedRecipient> findByUserIdAndPhoneNumberAndProviderId(
            UUID userId, String phoneNumber, String providerId);

    boolean existsByUserIdAndPhoneNumberAndProviderId(
            UUID userId, String phoneNumber, String providerId);

    /**
     * Checks if a recipient has any associated transactions.
     * This is used to prevent deletion of recipients with transaction history.
     */
    @Query("SELECT COUNT(t) > 0 FROM Transaction t WHERE t.recipientId = :recipientId")
    boolean hasTransactions(@Param("recipientId") UUID recipientId);
}
