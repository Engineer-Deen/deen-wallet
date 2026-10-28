package com.glr.deenwallet.admin;

/** The signed-in admin is fine, but is not allowed to do this. It must NOT sign them out. */
public class AdminAccessException extends SecurityException {
    public AdminAccessException(String message) {
        super(message);
    }
}
