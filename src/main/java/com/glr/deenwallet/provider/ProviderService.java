package com.glr.deenwallet.provider;

import com.glr.deenwallet.config.ProviderConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {

    private final ProviderConfig providerConfig;

    public Map<String, List<String>> getAllPrefixes() {
        return providerConfig.getPrefixes();
    }

    public String detectProvider(String phoneNumber) {
        String number = phoneNumber.replaceFirst("^\\+?232", "");
        Map<String, List<String>> prefixes = providerConfig.getPrefixes();

        for (int len = 2; len >= 1; len--) {
            if (number.length() >= len) {
                String prefix = number.substring(0, len);
                for (Map.Entry<String, List<String>> entry : prefixes.entrySet()) {
                    if (entry.getValue().contains(prefix)) {
                        return entry.getKey();
                    }
                }
            }
        }
        return providerConfig.getDefaultProvider();
    }
}
