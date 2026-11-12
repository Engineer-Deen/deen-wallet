package com.glr.deenwallet.monime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.monime")
public record MonimeProperties(
        String baseUrl,
        String accessToken,
        String spaceId,
        String webhookSecret,
        String defaultFinancialAccountId,
        /** Separate Monime financial account that receives WAEC pin payments (optional; empty = everything stays in the main account). */
        String waecFinancialAccountId,
        /** Optional ISO time (e.g. 2026-11-13T09:00:00Z). WAEC orders created before it were paid into the main account, so their refunds still come from it. */
        String waecAccountSince
) {
}
