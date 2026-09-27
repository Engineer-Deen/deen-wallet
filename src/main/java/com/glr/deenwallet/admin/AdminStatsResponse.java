package com.glr.deenwallet.admin;

public record AdminStatsResponse(
        long totalUsers,
        long verifiedUsers,
        long totalTransactions,
        double totalVolumeSle,
        long lockedUsers,
        long activeUsers
) {
}
