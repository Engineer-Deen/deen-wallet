package com.glr.deenwallet.recipient;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SavedRecipientService {

    private final SavedRecipientRepository savedRecipientRepository;

    public List<SavedRecipient> listForUser(UUID userId) {
        return savedRecipientRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public SavedRecipient save(UUID userId, SaveRecipientRequest request) {
        boolean alreadySaved = savedRecipientRepository.existsByUserIdAndPhoneNumberAndProviderId(
                userId, request.getPhoneNumber(), request.getProviderId());

        if (alreadySaved) {
            throw new IllegalArgumentException("This recipient is already saved");
        }

        SavedRecipient recipient = SavedRecipient.builder()
                .userId(userId)
                .phoneNumber(request.getPhoneNumber())
                .providerId(request.getProviderId())
                .holderName(request.getHolderName())
                .label(request.getLabel())
                .build();

        return savedRecipientRepository.save(recipient);
    }

    /**
     * Updates a recipient. Supports both label-only updates and full updates.
     * - If only label is provided: updates just the label
     * - If phoneNumber and providerId are provided: updates all fields
     *   (useful for correcting mistakes or when provider detection changes)
     */
    @Transactional
    public SavedRecipient update(UUID userId, UUID recipientId, UpdateRecipientRequest request) {
        SavedRecipient recipient = savedRecipientRepository.findById(recipientId)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));

        if (!recipient.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You do not have access to this recipient");
        }

        // Always update label if provided (even if null or empty string)
        recipient.setLabel(request.getLabel());

        // If phoneNumber is provided, update all related fields
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isEmpty()) {
            // Check if another recipient with this phone already exists
            boolean alreadyExists = savedRecipientRepository.existsByUserIdAndPhoneNumberAndProviderId(
                    userId, request.getPhoneNumber(), request.getProviderId());

            // If the existing one is a different recipient, prevent duplicate
            if (alreadyExists && !recipient.getPhoneNumber().equals(request.getPhoneNumber())) {
                throw new IllegalArgumentException("This phone number is already saved for another recipient");
            }

            recipient.setPhoneNumber(request.getPhoneNumber());

            if (request.getProviderId() != null && !request.getProviderId().isEmpty()) {
                recipient.setProviderId(request.getProviderId());
            }

            if (request.getHolderName() != null) {
                recipient.setHolderName(request.getHolderName());
            }
        }

        return savedRecipientRepository.save(recipient);
    }

    /**
     * Deletes a recipient. Checks if the recipient has any transactions
     * before deletion to prevent orphaned records or data integrity issues.
     * If transactions exist, the deletion is prevented with a clear message.
     */
    @Transactional
    public void delete(UUID userId, UUID recipientId) {
        SavedRecipient recipient = savedRecipientRepository.findById(recipientId)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));

        if (!recipient.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You do not have access to this recipient");
        }

        // Check if this recipient has any transactions
        // Using the repository method to check existence
        boolean hasTransactions = savedRecipientRepository.hasTransactions(recipientId);

        if (hasTransactions) {
            throw new IllegalStateException(
                    "Cannot delete this recipient because it has transaction history. " +
                            "You can hide it from the list instead, or contact support for assistance."
            );
        }

        savedRecipientRepository.delete(recipient);
    }
}
