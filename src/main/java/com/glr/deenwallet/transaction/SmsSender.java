package com.glr.deenwallet.transaction;

public interface SmsSender {
    void send(String phoneNumber, String message);
}
