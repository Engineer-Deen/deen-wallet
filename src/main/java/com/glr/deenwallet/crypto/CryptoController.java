package com.glr.deenwallet.crypto;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The signed-in customer's crypto payments. Protected by role in SecurityConfig (/api/crypto/** = customers). */
@RestController
@RequestMapping("/api/crypto")
public class CryptoController {

    private final CryptoOrderService service;

    public CryptoController(CryptoOrderService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(service.config(me()));
    }

    @GetMapping("/quote")
    public ResponseEntity<Map<String, Object>> quote(@RequestParam BigDecimal amount) {
        return ResponseEntity.ok(service.quote(me(), amount));
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CryptoOrderService.CreateRequest body) {
        return ResponseEntity.ok(service.create(me(), body));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<Map<String, Object>>> history() {
        return ResponseEntity.ok(service.history(me()));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable UUID id) {
        return ResponseEntity.ok(service.status(me(), id));
    }

    private UUID me() {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return UUID.fromString(userId);
    }
}
