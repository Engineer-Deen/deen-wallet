package com.glr.deenwallet.transaction;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class BankTransferRequest {
    private UUID recipientId;
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

    @NotBlank(message = "Bank provider is required")
    @Size(max = 64, message = "Bank provider is invalid")
    private String bankProviderId;

    @NotBlank(message = "Bank account number is required")
    @Size(min = 1, max = 64, message = "Bank account number is invalid")
    private String bankAccountNumber;
}
