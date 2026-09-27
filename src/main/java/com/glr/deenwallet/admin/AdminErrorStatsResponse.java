package com.glr.deenwallet.admin;

import java.util.List;

public record AdminErrorStatsResponse(
        long totalErrors,
        long errorsLast24h,
        long errorsLast7d,
        List<ErrorTypeCount> byType
) {
    public record ErrorTypeCount(
            String errorType,
            long count
    ) {
    }
}
