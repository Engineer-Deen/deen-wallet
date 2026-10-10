package com.glr.deenwallet.transaction;

/** Mapped to HTTP 429 by GlobalExceptionHandler. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) { super(message); }
}
