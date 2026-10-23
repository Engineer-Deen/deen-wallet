package com.glr.deenwallet.kyc;

/** Life cycle of one identity verification. A user has at most one open (DRAFT or PENDING) submission. */
public enum KycStatus {
    /** The customer is still filling it in. */
    DRAFT,
    /** Submitted, waiting for an admin. */
    PENDING,
    /** An admin verified the identity. Raises the daily limits. */
    APPROVED,
    /** An admin rejected it (a reason is stored). The customer may try again. */
    REJECTED,
    /** An admin withdrew a previously approved verification. */
    REVOKED
}
