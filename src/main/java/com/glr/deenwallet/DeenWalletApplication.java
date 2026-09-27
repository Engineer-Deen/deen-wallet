package com.glr.deenwallet;

import com.glr.deenwallet.monime.MonimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(MonimeProperties.class)
@EnableScheduling
public class DeenWalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeenWalletApplication.class, args);
    }

}
