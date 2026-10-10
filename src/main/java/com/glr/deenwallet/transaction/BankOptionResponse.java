package com.glr.deenwallet.transaction;

/**
 * One bank in the picker. {@code available} is false when the bank is listed by Monime
 * but cannot currently be used (inactive, payouts off, or name lookup off), so the UI can
 * show it greyed out with a friendly "check back later" message instead of hiding it.
 * {@code logoUrl} is set only when a logo file for this bank exists in static/assets/bank-logos.
 */
public record BankOptionResponse(
        String providerId,
        String name,
        boolean canVerifyAccount,
        boolean available,
        String logoUrl
) {
}