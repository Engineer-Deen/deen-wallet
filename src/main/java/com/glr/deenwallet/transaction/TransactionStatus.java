package com.glr.deenwallet.transaction;

public enum TransactionStatus {
    AWAITING_PAYMENT, PAID_IN, PAYING_OUT, COMPLETED, FAILED, CANCELED,
    /** Customer paid, payout failed. Waits for an admin to RESEND or REFUND (Failed Payouts section). */
    PAYOUT_FAILED,
    /** An admin approved a refund; it is being sent back to the paying number. */
    REFUND_PENDING,
    /** The refund payout completed. */
    REFUNDED,
    /** The refund itself was rejected by the provider: needs manual handling outside the app. */
    NEEDS_REVIEW
}
