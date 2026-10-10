package com.glr.deenwallet.transaction;

public record BankAccountVerificationResponse(
        boolean verified,
        boolean verificationSupported,
        String providerId,
        String bankName,
        String accountNumber,
        String holderName,
        String message,
        String verificationToken
) {
}
