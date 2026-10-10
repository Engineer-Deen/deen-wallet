package com.glr.deenwallet.recipient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveBankRecipientRequest {

    @NotBlank(message = "Bank provider is required")
    @Size(max = 64, message = "Bank provider is invalid")
    private String bankProviderId;

    @NotBlank(message = "Bank account number is required")
    @Size(min = 1, max = 64, message = "Bank account number is invalid")
    private String bankAccountNumber;

    @NotBlank(message = "Bank account verification is required")
    private String verificationToken;

    @Size(max = 50, message = "Label must be at most 50 characters")
    private String label;
}
