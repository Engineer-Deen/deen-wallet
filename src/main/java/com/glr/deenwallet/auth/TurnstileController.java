package com.glr.deenwallet.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public: the login pages ask here whether to show the "Verify you are human" box, and with which site key. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class TurnstileController {

    private final TurnstileService turnstile;

    @GetMapping("/turnstile")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("enabled", turnstile.isEnabled(), "siteKey", turnstile.getSiteKey()));
    }
}
