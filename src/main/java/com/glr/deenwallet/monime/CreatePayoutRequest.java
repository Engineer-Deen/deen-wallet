package com.glr.deenwallet.monime;

/**
 * Matches Monime's CreatePayout request body. "source" is left null in
 * most cases, letting Monime debit the default main financial account;
 * "destination" targets mobile money specifically (type = "momo"),
 * since bank and digital wallet payouts aren't used in this project.
 */
public record CreatePayoutRequest(
        Money amount,
        PayoutSource source,
        MomoDestination destination
) {

    public record PayoutSource(String financialAccountId) {
    }

    public record MomoDestination(String type, String providerId, String phoneNumber) {
        public static MomoDestination of(String providerId, String phoneNumber) {
            return new MomoDestination("momo", providerId, phoneNumber);
        }
    }

    public static CreatePayoutRequest toMobileMoney(Money amount, String providerId, String phoneNumber,
                                                    String sourceFinancialAccountId) {
        PayoutSource source = sourceFinancialAccountId != null
                ? new PayoutSource(sourceFinancialAccountId)
                : null;
        return new CreatePayoutRequest(amount, source, MomoDestination.of(providerId, phoneNumber));
    }
}

