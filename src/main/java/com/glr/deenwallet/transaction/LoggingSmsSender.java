package com.glr.deenwallet.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String message) {
        log.info("[SMS to {}] {}", phoneNumber, message);
    }
}
