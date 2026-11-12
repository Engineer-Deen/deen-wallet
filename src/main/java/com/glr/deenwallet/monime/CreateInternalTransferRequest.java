package com.glr.deenwallet.monime;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Body for POST /v1/internal-transfers: moves money between two financial accounts of the same Monime space. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateInternalTransferRequest(
        Money amount,
        Account sourceFinancialAccount,
        Account destinationFinancialAccount,
        String description
) {
    public record Account(String id) {
    }

    public static CreateInternalTransferRequest of(Money amount, String sourceId, String destinationId, String description) {
        return new CreateInternalTransferRequest(amount, new Account(sourceId), new Account(destinationId), description);
    }
}
