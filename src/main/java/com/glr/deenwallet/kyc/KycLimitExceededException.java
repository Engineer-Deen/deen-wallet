package com.glr.deenwallet.kyc;

/**
 * The customer asked to convert more than their daily limit allows. Extends IllegalArgumentException so
 * GlobalExceptionHandler already answers it with HTTP 400 and the message (never 401/403, which the app
 * would treat as an expired session).
 */
public class KycLimitExceededException extends IllegalArgumentException {
    public KycLimitExceededException(String message) { super(message); }
}
