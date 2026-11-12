package com.glr.deenwallet.transaction;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * "Withdraw from Bank": a user pays from their bank account (on the provider's hosted page) and the money arrives on a
 * mobile money number. Off until BANK_WITHDRAW_ENABLED=true, so it can be switched on alone.
 */
@RestController
@RequestMapping("/api/transactions/bank-withdraw")
@RequiredArgsConstructor
public class BankWithdrawController {

    private final TransactionService transactionService;

    @Value("${BANK_WITHDRAW_ENABLED:false}")
    private boolean enabled;

    @Value("${app.public-base-url:https://api.deenwallapp.com}")
    private String publicBaseUrl;

    @Getter
    @Setter
    public static class StartRequest {
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
        @DecimalMax(value = "50000.00", message = "Amount exceeds the maximum allowed per transaction")
        @Digits(integer = 12, fraction = 2, message = "Amount must have at most 2 decimal places")
        private BigDecimal amount;

        @NotBlank(message = "Destination provider is required")
        @Pattern(regexp = "m17|m18", message = "Only Orange Money or Africell is supported as the destination provider")
        private String destinationProviderId;

        @NotBlank(message = "Destination phone number is required")
        @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX")
        private String destinationPhone;
    }

    public record Config(boolean enabled, int feePercent) {}

    public record Started(TransactionResponse transaction, String redirectUrl) {}

    @GetMapping("/config")
    public ResponseEntity<Config> config() {
        return ResponseEntity.ok(new Config(enabled, 3));
    }

    @PostMapping
    public ResponseEntity<Started> start(@Valid @RequestBody StartRequest request) {
        if (!enabled) throw new IllegalStateException("Withdraw from Bank is coming soon.");
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
        TransactionService.BankWithdrawStart s = transactionService.initiateBankWithdrawal(
                currentUserId(), request.getDestinationProviderId(), request.getDestinationPhone(),
                request.getAmount(), base + "/bank-return.html");
        return ResponseEntity.ok(new Started(TransactionResponse.from(s.transaction()), s.redirectUrl()));
    }

    private UUID currentUserId() {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return UUID.fromString(userId);
    }
}
