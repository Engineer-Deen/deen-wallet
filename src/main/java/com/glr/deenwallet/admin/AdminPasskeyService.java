package com.glr.deenwallet.admin;

import com.glr.deenwallet.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passkeys for admins: sign in with the phone's screen lock instead of an email code.
 * Settings (optional, in the env file): WEBAUTHN_RP_ID (default deenwallapp.com) and WEBAUTHN_ORIGINS
 * (comma list, default https://deenwallapp.com, https://www.deenwallapp.com, https://api.deenwallapp.com).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPasskeyService {

    public static final int MAX_PASSKEYS = 5;
    private static final long CHALLENGE_MILLIS = 5 * 60 * 1000L;
    private static final String BAD = "Passkey check failed. Try again, or use Email or a Passcode.";

    private final AdminPasskeyRepository repo;
    private final PasswordEncoder encoder;

    @Value("${deenwallet.webauthn.rp-id:deenwallapp.com}")
    private String rpId;

    @Value("${deenwallet.webauthn.origins:https://deenwallapp.com,https://www.deenwallapp.com,https://api.deenwallapp.com}")
    private String originsCsv;

    /** The Android app's signing fingerprint (same value as for assetlinks.json). The app may report its own origin. */
    @Value("${android.app.sha256:}")
    private String androidSha256;

    private final SecureRandom random = new SecureRandom();

    private record Challenge(byte[] bytes, String purpose, UUID adminId, long expires) {}

    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    private Set<String> origins() {
        Set<String> s = new HashSet<>();
        for (String o : originsCsv.split(",")) {
            String t = o.trim();
            if (!t.isEmpty()) s.add(t);
        }
        for (String fp : androidSha256.split(",")) {
            String hex = fp.replace(":", "").trim();
            if (hex.length() != 64) continue;
            try {
                byte[] raw = new byte[32];
                for (int i = 0; i < 32; i++) raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                s.add("android:apk-key-hash:" + WebAuthnCore.b64u(raw));
            } catch (NumberFormatException ignored) {
                // a malformed fingerprint is simply not used
            }
        }
        return s;
    }

    private String newChallenge(String purpose, UUID adminId, Map<String, Object> options) {
        long now = System.currentTimeMillis();
        if (challenges.size() > 500) challenges.values().removeIf(c -> c.expires < now);
        if (challenges.size() > 5000) throw new IllegalStateException("Too many requests. Try again in a minute.");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        byte[] id = new byte[16];
        random.nextBytes(id);
        String challengeId = WebAuthnCore.b64u(id);
        challenges.put(challengeId, new Challenge(bytes, purpose, adminId, now + CHALLENGE_MILLIS));
        options.put("challenge", WebAuthnCore.b64u(bytes));
        return challengeId;
    }

    /** One use only: it is removed even when the answer turns out to be wrong. */
    private Challenge take(String challengeId, String purpose) {
        Challenge c = challengeId == null ? null : challenges.remove(challengeId);
        if (c == null || c.expires < System.currentTimeMillis() || !c.purpose.equals(purpose)) {
            throw new IllegalArgumentException("The passkey request expired. Try again.");
        }
        return c;
    }

    public List<AdminPasskey> list(UUID adminId) {
        return repo.findByAdminIdOrderByCreatedAtAsc(adminId);
    }

    // ------------------------------------------------------------ add a passkey (signed in)

    public Map<String, Object> registrationOptions(User admin, String password) {
        requirePassword(admin, password);
        List<AdminPasskey> mine = repo.findByAdminIdOrderByCreatedAtAsc(admin.getId());
        if (mine.size() >= MAX_PASSKEYS) {
            throw new IllegalArgumentException("You already have " + MAX_PASSKEYS + " passkeys. Delete one to add another.");
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rp", Map.of("id", rpId, "name", "DeenWallet Admin"));
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(admin.getId().getMostSignificantBits()).putLong(admin.getId().getLeastSignificantBits());
        String name = admin.getEmail() == null ? "admin" : admin.getEmail();
        String shown = admin.getFullName() == null || admin.getFullName().isBlank() ? name : admin.getFullName();
        o.put("user", Map.of("id", WebAuthnCore.b64u(bb.array()), "name", name, "displayName", shown));
        o.put("pubKeyCredParams", List.of(
                Map.of("type", "public-key", "alg", WebAuthnCore.ES256),
                Map.of("type", "public-key", "alg", WebAuthnCore.RS256)));
        o.put("timeout", 120000);
        o.put("attestation", "none");
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("residentKey", "preferred");
        sel.put("requireResidentKey", false);
        sel.put("userVerification", "required");
        o.put("authenticatorSelection", sel);
        List<Map<String, Object>> exclude = new ArrayList<>();
        for (AdminPasskey p : mine) exclude.add(Map.of("type", "public-key", "id", p.getCredentialId()));
        o.put("excludeCredentials", exclude);
        String cid = newChallenge("register", admin.getId(), o);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("challengeId", cid);
        out.put("options", o);
        return out;
    }

    @Transactional
    public AdminPasskey finishRegistration(User admin, String challengeId, String label, String clientDataJson, String attestationObject) {
        Challenge c = take(challengeId, "register");
        if (!admin.getId().equals(c.adminId)) throw new IllegalArgumentException("The passkey request expired. Try again.");
        if (repo.countByAdminId(admin.getId()) >= MAX_PASSKEYS) {
            throw new IllegalArgumentException("You already have " + MAX_PASSKEYS + " passkeys. Delete one to add another.");
        }
        WebAuthnCore.Registered r = WebAuthnCore.verifyRegistration(clientDataJson, attestationObject, c.bytes, rpId, origins());
        String credId = WebAuthnCore.b64u(r.credentialId());
        if (repo.findByCredentialId(credId).isPresent()) throw new IllegalArgumentException("This passkey is already added.");
        String name = label == null ? "" : label.trim();
        if (name.isEmpty()) name = "Passkey";
        if (name.length() > 40) name = name.substring(0, 40);
        return repo.save(AdminPasskey.builder()
                .adminId(admin.getId())
                .label(name)
                .credentialId(credId)
                .publicKey(Base64.getEncoder().encodeToString(r.publicKeySpki()))
                .alg(r.alg())
                .signCount(r.signCount())
                .build());
    }

    @Transactional
    public void delete(User admin, UUID id, String password) {
        requirePassword(admin, password);
        AdminPasskey p = repo.findByIdAndAdminId(id, admin.getId())
                .orElseThrow(() -> new IllegalArgumentException("Passkey not found."));
        repo.delete(p);
    }

    // ------------------------------------------------------------ use a passkey (not signed in)

    /** adminOrNull: the admin typed on the login screen, if any. Unknown or empty = the phone lists its own passkeys. */
    public Map<String, Object> loginOptions(User adminOrNull) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rpId", rpId);
        o.put("timeout", 120000);
        o.put("userVerification", "required");
        List<Map<String, Object>> allow = new ArrayList<>();
        if (adminOrNull != null) {
            for (AdminPasskey p : repo.findByAdminIdOrderByCreatedAtAsc(adminOrNull.getId())) {
                allow.add(Map.of("type", "public-key", "id", p.getCredentialId()));
            }
        }
        o.put("allowCredentials", allow);
        String cid = newChallenge("login", null, o);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("challengeId", cid);
        out.put("options", o);
        return out;
    }

    /** Returns the id of the admin who owns the passkey after the signature is verified. */
    @Transactional
    public UUID verifyLogin(String challengeId, String credentialId, String clientDataJson, String authenticatorData, String signature) {
        Challenge c = take(challengeId, "login");
        try {
            AdminPasskey p = repo.findByCredentialId(credentialId == null ? "" : credentialId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown passkey"));
            long count = WebAuthnCore.verifyAssertion(clientDataJson, authenticatorData, signature, c.bytes, rpId, origins(),
                    Base64.getDecoder().decode(p.getPublicKey()), p.getAlg(), p.getSignCount());
            p.setSignCount(count);
            p.setLastUsedAt(Instant.now());
            repo.save(p);
            return p.getAdminId();
        } catch (IllegalArgumentException e) {
            log.warn("Passkey login refused: {}", e.getMessage());
            throw new IllegalArgumentException(BAD);
        }
    }

    private void requirePassword(User admin, String password) {
        if (password == null || !encoder.matches(password, admin.getPasswordHash())) {
            throw new IllegalArgumentException("Your account password is not correct.");
        }
    }
}
