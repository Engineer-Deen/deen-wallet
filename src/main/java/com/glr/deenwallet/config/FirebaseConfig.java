package com.glr.deenwallet.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Initializes Firebase Admin (used for FCM push notifications) from a
 * service-account JSON key file. Download this file from
 * Firebase Console -> Project Settings -> Service Accounts -> Generate new
 * private key. NEVER commit it to git - point FIREBASE_SERVICE_ACCOUNT_PATH
 * at it from outside the repo (same treatment as .env).
 *
 * This is intentionally non-fatal: if the key isn't configured, push
 * notifications are simply unavailable (PushNotificationService no-ops and
 * logs a warning per call) rather than the whole app failing to start.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.firebase.service-account-path:}")
    private String serviceAccountPath;

    @PostConstruct
    public void init() {
        if (serviceAccountPath == null || serviceAccountPath.isBlank()) {
            log.warn("FIREBASE_SERVICE_ACCOUNT_PATH not set - push notifications are disabled.");
            return;
        }
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }
        try (FileInputStream serviceAccount = new FileInputStream(serviceAccountPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase Admin SDK initialized - push notifications are enabled.");
        } catch (IOException e) {
            log.error("Failed to initialize Firebase Admin SDK from '{}' - push notifications are disabled.", serviceAccountPath, e);
        }
    }
}
