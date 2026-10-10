package com.glr.deenwallet.monime;

import java.util.List;
import java.util.Map;

/**
 * Monime Bank object for caph.2025-08-23.
 */
public record BankResult(
        String providerId,
        String name,
        String country,
        Status status,
        FeatureSet featureSet,
        String createTime,
        String updateTime
) {
    public record Status(boolean active) {
    }

    public record FeatureSet(
            PayoutFeature payout,
            PaymentFeature payment,
            KycVerification kycVerification
    ) {
    }

    public record PayoutFeature(
            boolean canPayTo,
            List<String> schemes,
            Map<String, Object> metadata
    ) {
    }

    public record PaymentFeature(
            boolean canPayFrom,
            List<String> schemes,
            Map<String, Object> metadata
    ) {
    }

    public record KycVerification(
            boolean canVerifyAccount,
            Map<String, Object> metadata
    ) {
    }
}
