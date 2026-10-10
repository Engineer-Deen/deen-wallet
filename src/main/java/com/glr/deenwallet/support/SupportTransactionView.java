package com.glr.deenwallet.support;

import com.glr.deenwallet.transaction.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SupportTransactionView(
        UUID transactionId,
        String transactionCode,  // ✅ ADDED
        String status,
        BigDecimal amount,
        BigDecimal feeCharged,
        BigDecimal totalCharged,
        String sourceProviderId,
        String sourcePhone,
        String destinationProviderId,
        String destinationPhone,
        String destinationHolderName,
        String monimePaymentCodeId,
        String monimePayoutId,
        boolean smsSent,
        Instant createdAt,
        Instant updatedAt,
        String accountHolderEmail,
        String accountHolderAccountNumber,
        String serviceType,
        String bankProviderId,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolderName,
        boolean bankAccountKycVerified
) {
    public static SupportTransactionView from(Transaction t, String accountHolderEmail, String accountNumber) {
        return new SupportTransactionView(
                t.getId(),
                t.getTransactionCode(),  // ✅ ADDED
                t.getStatus().name(),
                toDecimal(t.getAmountValue()),
                toDecimal(t.getFeeValue()),
                toDecimal(t.getTotalChargedValue()),
                t.getSourceProviderId(),
                t.getSourcePhone(),
                t.getDestinationProviderId(),
                t.getDestinationPhone(),
                t.getDestinationHolderName(),
                t.getMonimePaymentCodeId(),
                t.getMonimePayoutId(),
                t.isSmsSent(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                accountHolderEmail,
                accountNumber,
                t.getServiceType() == null ? "MOBILE_MONEY" : t.getServiceType().name(),
                t.getDestinationBankProviderId(),
                t.getDestinationBankName(),
                t.getDestinationBankAccountNumber(),
                t.getDestinationBankHolderName(),
                t.isDestinationBankKycVerified()
        );
    }

    private static BigDecimal toDecimal(Long minorUnits) {
        return minorUnits == null ? null : BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }
}
