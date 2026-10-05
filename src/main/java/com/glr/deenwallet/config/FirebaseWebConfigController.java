package com.glr.deenwallet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class FirebaseWebConfigController {

    @Value("${app.firebase.web-config-path:}")
    private String webConfigPath;

    @GetMapping(
            value = "/api/config/firebase-web",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> getFirebaseWebConfig() {

        if (webConfigPath == null || webConfigPath.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path path = Path.of(webConfigPath);

            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(
                    Files.readString(path)
            );

        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}