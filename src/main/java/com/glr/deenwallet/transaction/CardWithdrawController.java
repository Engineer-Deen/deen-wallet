package com.glr.deenwallet.transaction;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "Withdraw from Card": a user pays by card on the provider's hosted page and the money arrives on a mobile money number.
 * Off until CARD_WITHDRAW_ENABLED=true, so it can be switched on alone (separately from Withdraw from Bank).
 */
@RestController
@RequestMapping("/api/transactions/card-withdraw")
@RequiredArgsConstructor
public class CardWithdrawController {

    private final TransactionService transactionService;
    private final com.glr.deenwallet.kyc.KycLimitService kycLimits;

    @Value("${CARD_WITHDRAW_ENABLED:false}")
    private boolean enabled;

    @Value("${app.public-base-url:https://api.deenwallapp.com}")
    private String publicBaseUrl;

    @GetMapping("/config")
    public ResponseEntity<BankWithdrawController.Config> config() {
        return ResponseEntity.ok(new BankWithdrawController.Config(enabled, 3, kycLimits.isVerified(currentUserId())));
    }

    @PostMapping
    public ResponseEntity<BankWithdrawController.Started> start(@Valid @RequestBody BankWithdrawController.StartRequest request) {
        if (!enabled) throw new IllegalStateException("Withdraw from Card is coming soon.");
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        TransactionService.BankWithdrawStart s = transactionService.initiateCardWithdrawal(
                currentUserId(), request.getDestinationProviderId(), request.getDestinationPhone(),
                request.getAmount(), base + "/bank-return.html");
        return ResponseEntity.ok(new BankWithdrawController.Started(TransactionResponse.from(s.transaction()), s.redirectUrl()));
    }

    private UUID currentUserId() {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return UUID.fromString(userId);
    }
}
