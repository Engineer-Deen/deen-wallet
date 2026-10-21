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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountLookupController {

    private final MonimeClient monimeClient;
    private final ProviderConfig providerConfig;

    /** Same number looked up again within this time is answered from memory, not from Monime. */
    private static final Duration LOOKUP_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHED_LOOKUPS = 5_000;
    private record CachedLookup(DetectProviderResponse response, Instant expiresAt) {}
    private final Map<String, CachedLookup> lookupCache = new ConcurrentHashMap<>();

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

        String cacheKey = providerId + "|" + normalized;
        CachedLookup hit = lookupCache.get(cacheKey);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return ResponseEntity.ok(hit.response());
        }

        try {
            ProviderKycResult kyc = monimeClient.getProviderKyc(providerId, normalized);
            if (kyc != null && kyc.account() != null) {
                DetectProviderResponse response = new DetectProviderResponse(providerId, kyc.account().holderName());
                if (lookupCache.size() >= MAX_CACHED_LOOKUPS) {
                    Instant now = Instant.now();
                    lookupCache.values().removeIf(c -> c.expiresAt().isBefore(now));
                }
                if (lookupCache.size() < MAX_CACHED_LOOKUPS) {
                    lookupCache.put(cacheKey, new CachedLookup(response, Instant.now().plus(LOOKUP_TTL)));
                }
                return ResponseEntity.ok(response);
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