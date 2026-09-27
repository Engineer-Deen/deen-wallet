package com.glr.deenwallet.monime;

import java.util.List;

/**
 * Every Monime API response follows this same envelope, regardless of
 * which endpoint you're calling. T is whatever the specific endpoint
 * returns in "result", e.g. a PaymentCodeResult or PayoutResult.
 */
public record MonimeEnvelope<T>(
        boolean success,
        List<String> messages,
        T result
) {
}
