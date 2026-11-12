package com.glr.deenwallet.monime;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Body for POST /v1/checkout-sessions. Only the fields this app uses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateCheckoutSessionRequest(
        String name,
        String description,
        String successUrl,
        String cancelUrl,
        String reference,
        List<Item> lineItems,
        Map<String, Object> paymentOptions,
        Map<String, String> metadata
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String type, String name, Money price, int quantity, String reference) {
    }

    /** A checkout that offers card payment only: mobile money, bank and wallet are switched off. */
    public static CreateCheckoutSessionRequest cardOnly(String name, String successUrl, String cancelUrl, String reference,
                                                        long amountMinor, long feeMinor) {
        java.util.ArrayList<Item> items = new java.util.ArrayList<>();
        items.add(new Item("custom", "Payment", new Money("SLE", amountMinor), 1, "item-" + reference));
        if (feeMinor > 0) items.add(new Item("custom", "Service fee", new Money("SLE", feeMinor), 1, "fee-" + reference));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("card", Map.of("disable", false));
        options.put("momo", Map.of("disable", true));
        options.put("bank", Map.of("disable", true));
        options.put("wallet", Map.of("disable", true));
        return new CreateCheckoutSessionRequest(name, null, successUrl, cancelUrl, reference, items, options,
                Map.of("transactionId", reference));
    }

    /** A checkout that offers bank payment only: card, mobile money and wallet are switched off. */
    public static CreateCheckoutSessionRequest bankOnly(String name, String successUrl, String cancelUrl, String reference,
                                                        long amountMinor, long feeMinor) {
        java.util.ArrayList<Item> items = new java.util.ArrayList<>();
        items.add(new Item("custom", "Withdrawal", new Money("SLE", amountMinor), 1, "item-" + reference));
        if (feeMinor > 0) items.add(new Item("custom", "Service fee", new Money("SLE", feeMinor), 1, "fee-" + reference));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("bank", Map.of("disable", false));
        options.put("card", Map.of("disable", true));
        options.put("momo", Map.of("disable", true));
        options.put("wallet", Map.of("disable", true));
        return new CreateCheckoutSessionRequest(name, null, successUrl, cancelUrl, reference, items, options,
                Map.of("transactionId", reference));
    }
}
