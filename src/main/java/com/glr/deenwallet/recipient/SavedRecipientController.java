package com.glr.deenwallet.recipient;

import com.glr.deenwallet.monime.MonimeClient;
import com.glr.deenwallet.monime.ProviderKycResult;
import com.glr.deenwallet.transaction.BankAccountVerificationResponse;
import com.glr.deenwallet.transaction.BankTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/recipients")
@RequiredArgsConstructor
public class SavedRecipientController {

    private final SavedRecipientService savedRecipientService;
    private final MonimeClient monimeClient;
    private final BankTransferService bankTransferService;

    @GetMapping
    public ResponseEntity<List<SavedRecipient>> list() {
        return ResponseEntity.ok(savedRecipientService.listForUser(currentUserId()));
    }

    @PostMapping
    public ResponseEntity<SavedRecipient> save(@Valid @RequestBody SaveRecipientRequest request) {
        return ResponseEntity.ok(savedRecipientService.save(currentUserId(), request));
    }

    @PostMapping("/bank")
    public ResponseEntity<SavedRecipient> saveBank(@Valid @RequestBody SaveBankRecipientRequest request) {
        String accountNumber = com.glr.deenwallet.transaction.BankTransferService.normalizeAccountNumber(request.getBankAccountNumber());
        BankAccountVerificationResponse verification = bankTransferService.consumeVerification(
                currentUserId(), request.getVerificationToken(), request.getBankProviderId(), accountNumber);

        SaveBankRecipientRequest normalized = new SaveBankRecipientRequest();
        normalized.setBankProviderId(verification.providerId());
        normalized.setBankAccountNumber(verification.accountNumber());
        normalized.setVerificationToken(null);
        normalized.setLabel(request.getLabel());

        return ResponseEntity.ok(savedRecipientService.saveBank(
                currentUserId(), normalized, verification.bankName(), verification.holderName()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SavedRecipient> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRecipientRequest request) {
        return ResponseEntity.ok(savedRecipientService.update(currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        savedRecipientService.delete(currentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Looks up the account holder's name for a phone number + provider
     * ahead of saving a recipient, so the frontend can show the name
     * exactly as it's registered with the mobile money provider
     * instead of letting the user type (and possibly mistype) it.
     * Reuses the same Monime provider-KYC call that TransactionService
     * already relies on when initiating a transfer.
     */
    @GetMapping("/lookup-name")
    public ResponseEntity<RecipientNameLookupResponse> lookupName(
            @RequestParam String phone,
            @RequestParam String providerId) {
        try {
            ProviderKycResult kyc = monimeClient.getProviderKyc(providerId, phone);
            String holderName = (kyc != null && kyc.account() != null)
                    ? kyc.account().holderName()
                    : null;
            return ResponseEntity.ok(new RecipientNameLookupResponse(holderName));
        } catch (HttpClientErrorException ex) {
            // Monime rejected the phone/provider combo (invalid number,
            // no account found, etc.) — a clean 404 instead of a 500.
            return ResponseEntity.notFound().build();
        }
    }

    public record RecipientNameLookupResponse(String holderName) {
    }

    /**
     * JwtAuthFilter sets the authenticated user's ID (a UUID string) as
     * the principal, so every protected endpoint can pull the current
     * user this way without a separate lookup.
     */
    private UUID currentUserId() {
        String userId = (String) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
        return UUID.fromString(userId);
    }
}
