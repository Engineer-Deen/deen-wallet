package com.glr.deenwallet.monime;

import java.util.List;

public record CreatePaymentCodeRequest(
        String mode,
        String name,
        Money amount,
        String duration,
        List<String> authorizedProviders,
        String authorizedPhoneNumber,
        String reference
) {
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
                reference
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
                reference
        );
    }
}
