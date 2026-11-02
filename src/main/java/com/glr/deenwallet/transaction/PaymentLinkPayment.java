package com.glr.deenwallet.transaction;

import java.util.UUID;

/** Everything needed to start one guest payment on a payment link. Plain data, built by PaymentRequestService. */
public record PaymentLinkPayment(
        UUID ownerId,
        UUID requestId,
        boolean bank,
        String destProviderId,
        String destAccount,
        String destHolderName,
        String destBankName,
        long amountMinor,
        String payerProviderId,
        String payerPhone,
        String payerName
) {
}
