package com.glr.deenwallet.account;

import com.glr.deenwallet.config.ProviderConfig;
import com.glr.deenwallet.monime.MonimeClient;
import com.glr.deenwallet.monime.ProviderKycResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountLookupController {

    private final MonimeClient monimeClient;
    private final ProviderConfig providerConfig;

    @GetMapping("/detect-provider")
    public ResponseEntity<DetectProviderResponse> detectProvider(@RequestParam String phone) {
        String normalized = normalizePhone(phone);
        String local = normalized.replaceFirst("^\\+?232", "");
        String prefix2 = local.length() >= 2 ? local.substring(0, 2) : local;

        String providerId = providerFromConfiguredPrefix(normalized);
        if (providerId == null || providerId.isBlank()) {
            log.info("detect-provider: no configured provider for prefix={}", prefix2);
            return ResponseEntity.notFound().build();
        }

        try {
            ProviderKycResult kyc = monimeClient.getProviderKyc(providerId, normalized);
            if (kyc != null && kyc.account() != null) {
                return ResponseEntity.ok(new DetectProviderResponse(providerId, kyc.account().holderName()));
            }
            log.info("detect-provider: Monime returned no account for provider={} prefix={}", providerId, prefix2);
        } catch (HttpClientErrorException ex) {
            // The configured YAML provider is authoritative. Never silently
            // retry another provider.
            log.warn("detect-provider: Monime rejected lookup provider={} prefix={} status={}",
                    providerId, prefix2, ex.getStatusCode().value());
        }
        return ResponseEntity.notFound().build();
    }

    private String providerFromConfiguredPrefix(String phone) {
        String local = phone.replaceFirst("^\\+?232", "");
        var prefixes = providerConfig.getPrefixes();
        if (prefixes == null) return null;
        for (int len = 2; len >= 1; len--) {
            if (local.length() < len) continue;
            String prefix = local.substring(0, len);
            for (var entry : prefixes.entrySet()) {
                if (entry.getValue() != null && entry.getValue().contains(prefix)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private String normalizePhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+232")) return digits;
        if (digits.startsWith("232")) return "+" + digits;
        return "+232" + digits;
    }

    public record DetectProviderResponse(String providerId, String holderName) {}
}
