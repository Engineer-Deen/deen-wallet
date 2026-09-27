package com.glr.deenwallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.providers")
@Data
public class ProviderConfig {
    private Map<String, List<String>> prefixes;
    private String defaultProvider = "m17";
}
