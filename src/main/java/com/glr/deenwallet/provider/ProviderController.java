package com.glr.deenwallet.provider;

import com.glr.deenwallet.config.ProviderConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/providers")
@RequiredArgsConstructor
public class ProviderController {
    private final ProviderConfig providerConfig;

    @GetMapping("/prefixes")
    public ResponseEntity<Map<String, List<String>>> prefixes() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(providerConfig.getPrefixes());
    }
}
