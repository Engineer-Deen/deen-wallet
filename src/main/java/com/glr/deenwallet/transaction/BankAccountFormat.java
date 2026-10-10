package com.glr.deenwallet.transaction;

/**
 * Account-number rule shared by every bank: digits only, 10 to 18 digits
 * (normal accounts are roughly 10-13 digits, the BBAN is exactly 18).
 * Whether the number really exists at that bank is decided by Monime's lookup.
 *
 * Numbers only: no "/", "-", "." or spaces are accepted for any bank.
 */
public final class BankAccountFormat {

    public static final int MIN_DIGITS = 10;
    public static final int MAX_DIGITS = 18;

    private BankAccountFormat() {
    }

    /** Trims the input. Nothing else is removed: any non-digit makes the number invalid. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** True when the text is not empty and every character is 0-9 (ASCII digits only). */
    public static boolean isDigitsOnly(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Throws IllegalArgumentException with a friendly message when the number cannot be
     * valid. {@code normalizedAccount} must already be trimmed.
     */
    public static void validate(String bankName, String normalizedAccount) {
        boolean digitsOnly = isDigitsOnly(normalizedAccount);
        int length = normalizedAccount.length();
        if (!digitsOnly || length < MIN_DIGITS || length > MAX_DIGITS) {
            throw new IllegalArgumentException(
                    "Account numbers have " + MIN_DIGITS + " to " + MAX_DIGITS + " digits (numbers only).");
        }
    }
}