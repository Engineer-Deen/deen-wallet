package com.glr.deenwallet.monime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.monime")
public record MonimeProperties(
        String baseUrl,
        String accessToken,
        String spaceId,
        String webhookSecret,
        String defaultFinancialAccountId
) {
}
