package com.glr.deenwallet.monime;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MonimeClientConfig {

    private final MonimeProperties monimeProperties;

    @PostConstruct
    public void logCredentialStatus() {
        int accessTokenLength = monimeProperties.accessToken() != null ? monimeProperties.accessToken().length() : -1;
        int spaceIdLength = monimeProperties.spaceId() != null ? monimeProperties.spaceId().length() : -1;
        int webhookSecretLength = monimeProperties.webhookSecret() != null ? monimeProperties.webhookSecret().length() : -1;
        log.info("Monime credentials loaded - accessToken length: {}, spaceId length: {}, webhookSecret length: {}",
                accessTokenLength, spaceIdLength, webhookSecretLength);
    }

    @Bean
    public RestClient monimeRestClient() {
        return RestClient.builder()
                .baseUrl(monimeProperties.baseUrl())
                .defaultHeader("Authorization", "Bearer " + monimeProperties.accessToken())
                .defaultHeader("Monime-Space-Id", monimeProperties.spaceId())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    if (request.getMethod().name().equals("POST")
                            && !request.getHeaders().containsKey("Idempotency-Key")) {
                        request.getHeaders().add("Idempotency-Key", UUID.randomUUID().toString());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
