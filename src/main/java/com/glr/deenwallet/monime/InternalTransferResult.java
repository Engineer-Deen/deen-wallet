package com.glr.deenwallet.monime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalTransferResult(
        String id,
        String status,
        Money amount,
        String createTime,
        String updateTime,
        FailureDetail failureDetail
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FailureDetail(String code, String message) {
    }
}
