package com.glr.deenwallet.transaction;

import java.math.BigDecimal;

/**
 * Public-safe fee preview shape — mirrors what TransactionResponse
 * exposes (amount, fee, totalCharged) but before any transaction is
 * actually created. Never exposes the internal three-way fee
 * breakdown (monimeDepositFeeValue / deenWalletFeeValue /
 * monimeWithdrawalFeeValue), same as TransactionResponse.
 */
public record FeePreviewResponse(
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal totalCharged
) {
}
