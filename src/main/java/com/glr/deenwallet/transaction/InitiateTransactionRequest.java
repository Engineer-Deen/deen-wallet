package com.glr.deenwallet.transaction;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class InitiateTransactionRequest {

    // NOTE: 50,000.00 is a placeholder ceiling - there was previously no
    // upper bound at all on a single transfer. Replace this with your
    // actual per-transaction / AML compliance limit before launch, and
    // consider adding a rolling daily/weekly velocity limit per user on
    // top of this (this class only enforces a single-transaction cap).
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Amount must be at least 1.00")
    @DecimalMax(value = "50000.00", message = "Amount exceeds the maximum allowed per transaction")
    @Digits(integer = 12, fraction = 2, message = "Amount must have at most 2 decimal places")
    private BigDecimal amount;

    @NotBlank(message = "Source provider is required")
    @Pattern(regexp = "m17|m18", message = "Only Orange Money or Africell is supported as the funding provider")
    private String sourceProviderId;

    @NotBlank(message = "Source phone number is required")
    @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX")
    private String sourcePhone;

    @NotBlank(message = "Destination provider is required")
    @Pattern(regexp = "m17|m18", message = "Only Orange Money or Africell is supported as the destination provider")
    private String destinationProviderId;

    @NotBlank(message = "Destination phone number is required")
    @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX")
    private String destinationPhone;

    /**
     * Optional. If the destination was picked from a saved recipient,
     * the transaction links back to it for display purposes ("sent to
     * Mum" instead of a bare number).
     */
    private UUID recipientId;
}
