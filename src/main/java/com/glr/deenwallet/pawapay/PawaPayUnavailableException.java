package com.glr.deenwallet.pawapay;

/** The outcome of a pawaPay call is UNKNOWN (timeout, network error, HTTP 5xx). Never treat this as a failure: check the status later. */
public class PawaPayUnavailableException extends RuntimeException {
    public PawaPayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
