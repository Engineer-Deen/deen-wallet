package com.glr.deenwallet.waec;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encrypts WAEC pins at rest (AES-256-GCM) and produces keyed hashes (HMAC-SHA256) used only to
 * detect duplicates and to search. The key comes from WAEC_PIN_ENCRYPTION_KEY (base64, 32 bytes).
 * If the key is missing the service refuses to store or sell pins: it never falls back to plain text.
 */
@Slf4j
@Component
public class WaecCrypto {

    @Value("${app.waec.encryption-key:}")
    private String keyBase64;

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec aesKey;
    private SecretKeySpec hmacKey;

    @PostConstruct
    void init() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            log.error("WAEC_PIN_ENCRYPTION_KEY is not set: WAEC pin sales and pin uploads are DISABLED.");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WAEC_PIN_ENCRYPTION_KEY must be valid base64.");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("WAEC_PIN_ENCRYPTION_KEY must decode to exactly 32 bytes.");
        }
        aesKey = new SecretKeySpec(raw, "AES");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(raw, "HmacSHA256"));
            hmacKey = new SecretKeySpec(mac.doFinal("waec-hash-v1".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise WAEC hashing.", e);
        }
    }

    public boolean isConfigured() {
        return aesKey != null;
    }

    private void requireConfigured() {
        if (aesKey == null) {
            throw new IllegalStateException("WAEC pin service is not configured. Please contact support.");
        }
    }

    public String encrypt(String plain) {
        requireConfigured();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the pin.", e);
        }
    }

    public String decrypt(String stored) {
        requireConfigured();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt the pin (wrong or changed encryption key?).", e);
        }
    }

    public String hash(String normalizedValue) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash the pin.", e);
        }
    }
}
