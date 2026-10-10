package com.glr.deenwallet.transaction;

/** A bank (or Monime) cannot be used right now. Mapped to HTTP 503 with a customer-friendly message. */
public class BankKycUnavailableException extends RuntimeException {
    public BankKycUnavailableException(String message) {
        super(message);
    }

    public BankKycUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}