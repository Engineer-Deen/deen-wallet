package com.glr.deenwallet.transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deen Wallet's 3% conversion fee, split into three equal 1% parts:
 * the Monime deposit fee, Deen Wallet's own revenue, and the Monime
 * withdrawal fee. Same structure applies in both directions
 * (Orange Money <-> Africell Money). The frontend only ever shows the
 * combined feeValue as one line; the three-way split exists purely
 * for internal accounting and reconciliation.
 */
public record ConversionFee(
        long amountValue,
        long feeValue,
        long monimeDepositFeeValue,
        long deenWalletFeeValue,
        long monimeWithdrawalFeeValue,
        long totalChargedValue
) {

    private static final BigDecimal COMPONENT_RATE = new BigDecimal("0.01");

    public static ConversionFee calculate(long amountValue) {
        BigDecimal amount = BigDecimal.valueOf(amountValue);

        long monimeDeposit = amount.multiply(COMPONENT_RATE)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long deenWallet = amount.multiply(COMPONENT_RATE)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long monimeWithdrawal = amount.multiply(COMPONENT_RATE)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();

        long totalFee = monimeDeposit + deenWallet + monimeWithdrawal;
        long totalCharged = amountValue + totalFee;

        return new ConversionFee(
                amountValue,
                totalFee,
                monimeDeposit,
                deenWallet,
                monimeWithdrawal,
                totalCharged
        );
    }
}
