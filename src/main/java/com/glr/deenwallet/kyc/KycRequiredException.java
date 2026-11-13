package com.glr.deenwallet.kyc;

/**
 * A service that is only for ID-verified customers was used by someone who has not completed KYC. Extends
 * IllegalArgumentException so GlobalExceptionHandler answers it with HTTP 400 and the message (never 401/403,
 * which the app would treat as an expired session).
 */
public class KycRequiredException extends IllegalArgumentException {
    public KycRequiredException(String message) { super(message); }
}
