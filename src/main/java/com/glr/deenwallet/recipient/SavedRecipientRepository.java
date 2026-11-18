package com.glr.deenwallet.recipient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedRecipientRepository extends JpaRepository<SavedRecipient, UUID> {

    /** Send Money recipients (and saved bank accounts): the ones with no purpose. */
    List<SavedRecipient> findByUserIdAndPurposeIsNullOrderByCreatedAtDesc(UUID userId);

    /** Recipients saved for one service, e.g. BANK_WITHDRAW or CARD_WITHDRAW. */
    List<SavedRecipient> findByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId, String purpose);

    Optional<SavedRecipient> findByUserIdAndPhoneNumberAndProviderIdAndPurposeIsNull(
            UUID userId, String phoneNumber, String providerId);

    Optional<SavedRecipient> findByUserIdAndPhoneNumberAndProviderIdAndPurpose(
            UUID userId, String phoneNumber, String providerId, String purpose);

    boolean existsByUserIdAndPhoneNumberAndProviderIdAndPurposeIsNull(
            UUID userId, String phoneNumber, String providerId);

    boolean existsByUserIdAndPhoneNumberAndProviderIdAndPurpose(
            UUID userId, String phoneNumber, String providerId, String purpose);

    boolean existsByUserIdAndBankProviderIdAndBankAccountNumber(
            UUID userId, String bankProviderId, String bankAccountNumber);

    /**
     * Checks if a recipient has any associated transactions.
     * This is used to prevent deletion of recipients with transaction history.
     */
    @Query("SELECT COUNT(t) > 0 FROM Transaction t WHERE t.recipientId = :recipientId")
    boolean hasTransactions(@Param("recipientId") UUID recipientId);
}
