package com.glr.deenwallet.monime;

import java.util.List;

/** Response shape for GET /v1/banks, including cursor pagination. */
public record MonimeBankListResponse(
        boolean success,
        List<String> messages,
        List<BankResult> result,
        Pagination pagination
) {
    public record Pagination(Integer count, String next) {
    }
}
