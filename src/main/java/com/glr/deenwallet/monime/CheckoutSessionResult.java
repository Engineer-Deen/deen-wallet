package com.glr.deenwallet.monime;

import java.util.List;

/**
 * What Monime returns for a hosted checkout session (POST/GET /v1/checkout-sessions).
 * Fields follow Monime's published Checkout Session object. Unknown fields are ignored.
 */
public record CheckoutSessionResult(
        String id,
        String status,
        String name,
        String reference,
        String redirectUrl,
        String expireTime,
        LineItems lineItems
) {
    public record LineItems(List<Item> data) {
    }

    public record Item(String name, Money price, Long quantity) {
    }

    /** Sum of the line items in minor units, or null when the response carried none (then no amount check is made). */
    public Money total() {
        if (lineItems == null || lineItems.data() == null || lineItems.data().isEmpty()) return null;
        long sum = 0;
        String currency = null;
        for (Item i : lineItems.data()) {
            if (i == null || i.price() == null) return null;
            long q = i.quantity() == null ? 1L : i.quantity();
            sum += i.price().value() * q;
            if (currency == null) currency = i.price().currency();
        }
        return new Money(currency == null ? "SLE" : currency, sum);
    }
}
