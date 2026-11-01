package com.glr.deenwallet.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public: the apps and the welcome page read the current support WhatsApp number from here. */
@RestController
@RequiredArgsConstructor
public class SupportContactController {

    private final SupportContactService support;

    @GetMapping("/api/app/support-whatsapp")
    public ResponseEntity<Map<String, String>> whatsapp() {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("whatsappNumber", support.getWhatsapp()));
    }
}
