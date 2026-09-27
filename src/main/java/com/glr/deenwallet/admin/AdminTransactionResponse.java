package com.glr.deenwallet.admin;

import com.glr.deenwallet.transaction.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminTransactionResponse(
        UUID id,
        UUID userId,
        String status,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal monimeDepositFee,
        BigDecimal deenWalletFee,
        BigDecimal monimeWithdrawalFee,
        BigDecimal totalCharged,
        String sourceProviderId,
        String sourcePhone,
        String destinationProviderId,
        String destinationPhone,
        String destinationHolderName,
        String monimePaymentCodeId,
        String monimePayoutId,
        String ussdCode,
        String failureReason,
        boolean smsSent,
        Instant createdAt,
        Instant updatedAt,
        String transactionCode,
        String accountHolderName,
        String accountHolderEmail,
        String accountHolderAccountNumber
) {
    public static AdminTransactionResponse from(Transaction t) {
        return from(t, null);
    }

    public static AdminTransactionResponse from(Transaction t, com.glr.deenwallet.user.User user) {
        return new AdminTransactionResponse(
                t.getId(),
                t.getUserId(),
                t.getStatus().name(),
                toDecimal(t.getAmountValue()),
                toDecimal(t.getFeeValue()),
                toDecimal(t.getMonimeDepositFeeValue()),
                toDecimal(t.getDeenWalletFeeValue()),
                toDecimal(t.getMonimeWithdrawalFeeValue()),
                toDecimal(t.getTotalChargedValue()),
                t.getSourceProviderId(),
                t.getSourcePhone(),
                t.getDestinationProviderId(),
                t.getDestinationPhone(),
                t.getDestinationHolderName(),
                t.getMonimePaymentCodeId(),
                t.getMonimePayoutId(),
                t.getMonimeUssdCode(),
                t.getFailureReason(),
                t.isSmsSent(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getTransactionCode(),
                user == null ? null : user.getFullName(),
                user == null ? null : user.getEmail(),
                user == null ? null : user.getAccountNumber()
        );
    }

    private static BigDecimal toDecimal(Long minorUnits) {
        return minorUnits == null ? null : BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }
}
