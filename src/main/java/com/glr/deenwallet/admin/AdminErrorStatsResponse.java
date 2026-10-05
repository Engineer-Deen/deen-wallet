package com.glr.deenwallet.admin;

import java.util.List;

public record AdminErrorStatsResponse(
        long totalErrors,
        long errorsLast24h,
        long errorsLast7d,
        // User-app errors are surfaced first/prominently: it has far more real users
        // than the admin app, so issues there matter most and should not get buried.
        long userAppErrorsLast24h,
        long adminAppErrorsLast24h,
        List<ErrorTypeCount> byType
) {
    public record ErrorTypeCount(
            String errorType,
            long count
    ) {
    }
}
