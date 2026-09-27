package com.glr.deenwallet.monime;

/**
 * Monime represents money as a currency code plus an integer value in the
 * currency's minor unit. For SLE (Sierra Leonean Leone), 1 Leone is
 * represented as value = 100, the same way cents work for USD.
 */
public record Money(String currency, long value) {
}
