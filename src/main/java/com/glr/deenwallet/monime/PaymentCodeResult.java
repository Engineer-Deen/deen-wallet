package com.glr.deenwallet.monime;

/**
 * Best-effort shape of what Monime returns after creating a payment
 * code. The "id", "status", and echoed request fields follow the same
 * pattern used across Monime's other objects. The exact name of the
 * USSD code field (ussdCode here) was not directly confirmed against
 * their response schema, so verify this against a real test call
 * before relying on it in production.
 */
public record PaymentCodeResult(
        String id,
        String status,
        String mode,
        String name,
        Money amount,
        String ussdCode,
        String reference,
        String financialAccountId,
        String createTime,
        String updateTime
) {
}
