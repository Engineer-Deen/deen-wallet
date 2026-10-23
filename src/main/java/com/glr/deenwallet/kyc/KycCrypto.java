package com.glr.deenwallet.kyc;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Encrypts identity photos and document numbers at rest (AES-256-GCM) and makes keyed hashes
 * (HMAC-SHA256) used only to detect a document being reused on another account.
 *
 * Key: KYC_ENCRYPTION_KEY (base64, 32 bytes). If that is not set, a separate KYC key is DERIVED from
 * WAEC_PIN_ENCRYPTION_KEY, so the feature works with the key already in your env file. Without either
 * key the service refuses to store anything: it never falls back to plain text.
 *
 * Both sub-keys are derived with HMAC (one per purpose) so the AES key and the hash key differ.
 */
@Component
public class KycCrypto {

    private static final Logger log = LoggerFactory.getLogger(KycCrypto.class);
    private static final int IV_BYTES = 12;

    @Value("${KYC_ENCRYPTION_KEY:}")
    private String kycKeyBase64;

    @Value("${app.waec.encryption-key:}")
    private String waecKeyBase64;

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec aesKey;
    private SecretKeySpec hmacKey;

    @PostConstruct
    void init() {
        String source = kycKeyBase64 != null && !kycKeyBase64.isBlank() ? kycKeyBase64 : waecKeyBase64;
        if (source == null || source.isBlank()) {
            log.error("KYC_ENCRYPTION_KEY is not set (and no WAEC key to derive from): identity verification uploads are DISABLED.");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(source.trim());
        } catch (IllegalArgumentException e) {
            log.error("KYC_ENCRYPTION_KEY is not valid base64: identity verification uploads are DISABLED (payments are not affected).");
            return;
        }
        if (raw.length != 32) {
            log.error("KYC_ENCRYPTION_KEY must decode to exactly 32 bytes: identity verification uploads are DISABLED (payments are not affected).");
            return;
        }
        aesKey = new SecretKeySpec(derive(raw, "kyc-aes-v1"), "AES");
        hmacKey = new SecretKeySpec(derive(raw, "kyc-hash-v1"), "HmacSHA256");
    }

    private static byte[] derive(byte[] root, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(root, "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise KYC encryption.", e);
        }
    }

    public boolean isConfigured() {
        return aesKey != null;
    }

    private void requireConfigured() {
        if (aesKey == null) {
            throw new IllegalStateException("Identity verification is temporarily unavailable. Please try again later.");
        }
    }

    /**
     * Encrypts bytes. {@code context} (for example the submission id and photo kind) is authenticated
     * but not stored: a ciphertext copied onto another row will not decrypt.
     * Output layout: 12-byte IV followed by ciphertext+tag.
     */
    public byte[] encrypt(byte[] plain, String context) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Could not protect the document. Please try again.", e);
        }
    }

    public byte[] decrypt(byte[] stored, String context) {
        requireConfigured();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, stored, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the stored document (wrong or changed encryption key?).", e);
        }
    }

    public String encryptText(String plain, String context) {
        return Base64.getEncoder().encodeToString(encrypt(plain.getBytes(StandardCharsets.UTF_8), context));
    }

    public String decryptText(String stored, String context) {
        return new String(decrypt(Base64.getDecoder().decode(stored), context), StandardCharsets.UTF_8);
    }

    /** Keyed hash of an already-normalised value. */
    public String hash(String normalizedValue) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not process the document number.", e);
        }
    }
}
