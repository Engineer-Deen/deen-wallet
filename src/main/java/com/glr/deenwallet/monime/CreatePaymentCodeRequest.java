package com.glr.deenwallet.monime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record CreatePaymentCodeRequest(
        String mode,
        String name,
        Money amount,
        String duration,
        List<String> authorizedProviders,
        String authorizedPhoneNumber,
        String reference,
        /** Where the money is settled. Null = Monime's main account (field is then left out of the request). */
        @JsonInclude(JsonInclude.Include.NON_NULL) String financialAccountId
) {
    /** Same code, settled into the given financial account (blank or null keeps the main account). */
    public CreatePaymentCodeRequest withFinancialAccount(String accountId) {
        String id = accountId == null || accountId.isBlank() ? null : accountId.trim();
        return new CreatePaymentCodeRequest(mode, name, amount, duration, authorizedProviders, authorizedPhoneNumber, reference, id);
    }

    public static CreatePaymentCodeRequest oneTime(String name, Money amount,
                                                   String payerPhone,
                                                   String reference) {
        return new CreatePaymentCodeRequest(
                "one_time",
                name,
                amount,
                "10m",
                null,
                payerPhone,
                reference,
                null
        );
    }

    public static CreatePaymentCodeRequest oneTimeForProvider(String name, Money amount,
                                                              String providerId,
                                                              String payerPhone,
                                                              String reference) {
        return new CreatePaymentCodeRequest(
                "one_time",
                name,
                amount,
                "10m",
                List.of(providerId),
                payerPhone,
                reference,
                null
        );
    }
}
