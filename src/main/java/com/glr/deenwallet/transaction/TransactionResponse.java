package com.glr.deenwallet.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String transactionCode,  // ✅ ADDED - Human-readable code (DW-YYYYMMDD-XXXXX)
        String status,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal totalCharged,
        String destinationHolderName,
        String destinationPhone,
        String sourceProviderId,
        String destinationProviderId,
        String ussdCode,
        String failureReason,
        Instant createdAt,
        String serviceType,
        String bankProviderId,
        String bankName,
        String bankAccountNumber,
        String bankAccountHolderName,
        boolean bankAccountKycVerified
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getTransactionCode(),  // ✅ ADDED
                t.getStatus().name(),
                toDecimal(t.getAmountValue()),
                toDecimal(t.getFeeValue()),
                toDecimal(t.getTotalChargedValue()),
                t.getDestinationHolderName(),
                t.getDestinationPhone(),
                t.getSourceProviderId(),
                t.getDestinationProviderId(),
                t.getMonimeUssdCode(),
                t.getFailureReason(),
                t.getCreatedAt(),
                t.getServiceType() == null ? TransactionServiceType.MOBILE_MONEY.name() : t.getServiceType().name(),
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
