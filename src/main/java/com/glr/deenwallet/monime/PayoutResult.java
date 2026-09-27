package com.glr.deenwallet.monime;

public record PayoutResult(
        String id,
        String status,
        Money amount,
        String createTime,
        String updateTime
) {
}
