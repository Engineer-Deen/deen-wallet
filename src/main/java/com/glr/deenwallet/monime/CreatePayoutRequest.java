package com.glr.deenwallet.monime;

/**
 * Matches Monime's CreatePayout request body. Mobile-money and bank
 * destinations are supported through the two documented destination shapes.
 */
public record CreatePayoutRequest(
        Money amount,
        PayoutSource source,
        PayoutDestination destination
) {

    public record PayoutSource(String financialAccountId) {
    }

    public sealed interface PayoutDestination permits MomoDestination, BankDestination {
    }

    public record MomoDestination(String type, String providerId, String phoneNumber)
            implements PayoutDestination {
        public static MomoDestination of(String providerId, String phoneNumber) {
            return new MomoDestination("momo", providerId, phoneNumber);
        }
    }

    public record BankDestination(String type, String providerId, String accountNumber)
            implements PayoutDestination {
        public static BankDestination of(String providerId, String accountNumber) {
            return new BankDestination("bank", providerId, accountNumber);
        }
    }

    public static CreatePayoutRequest toMobileMoney(
            Money amount,
            String providerId,
            String phoneNumber,
            String sourceFinancialAccountId) {
        PayoutSource source = sourceFinancialAccountId != null
                ? new PayoutSource(sourceFinancialAccountId)
                : null;
        return new CreatePayoutRequest(amount, source, MomoDestination.of(providerId, phoneNumber));
    }

    public static CreatePayoutRequest toBank(
            Money amount,
            String providerId,
            String accountNumber,
            String sourceFinancialAccountId) {
        PayoutSource source = sourceFinancialAccountId != null
                ? new PayoutSource(sourceFinancialAccountId)
                : null;
        // Monime's bank destination requires type + providerId (the bank) + accountNumber.
        return new CreatePayoutRequest(amount, source, BankDestination.of(providerId, accountNumber));
    }
}