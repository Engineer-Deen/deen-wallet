package com.glr.deenwallet.pawapay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pawaPay settings. Everything stays off until PAWAPAY_ENABLED=true.
 * Sandbox: https://api.sandbox.pawapay.io  -  Production: https://api.pawapay.io (the token is different for each).
 */
@ConfigurationProperties(prefix = "app.pawapay")
public record PawaPayProperties(
        boolean enabled,
        String baseUrl,
        String apiToken,
        /** Secret part of the callback URL set in the pawaPay dashboard: /api/v1/webhooks/pawapay/{secret}/deposits ... */
        String callbackSecret
) {
    public boolean sandbox() {
        return baseUrl != null && baseUrl.contains("sandbox");
    }
}
