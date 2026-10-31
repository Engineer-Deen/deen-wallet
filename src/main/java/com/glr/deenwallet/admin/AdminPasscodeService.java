package com.glr.deenwallet.admin;

import com.glr.deenwallet.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Up to 5 recovery passcodes per admin, a second way back in that does not depend on the email service. */
@Service
@RequiredArgsConstructor
public class AdminPasscodeService {

    public static final int MAX_PASSCODES = 5;
    private static final int MIN_LEN = 8;
    private static final int MAX_LEN = 64;
    private static final int MAX_FAILURES = 5;
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(15);
    private static final String BAD = "Invalid passcode, or too many attempts. Wait a few minutes and try again.";

    private final AdminPasscodeRepository repo;
    private final PasswordEncoder encoder;

    /** email -> {failure count, window start millis} */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    public List<AdminPasscode> list(UUID adminId) {
        return repo.findByAdminIdOrderByCreatedAtAsc(adminId);
    }

    @Transactional
    public AdminPasscode add(User admin, String label, String passcode, String password) {
        requirePassword(admin, password);
        String code = passcode == null ? "" : passcode.trim();
        if (code.length() < MIN_LEN || code.length() > MAX_LEN) {
            throw new IllegalArgumentException("A passcode must be " + MIN_LEN + " to " + MAX_LEN + " characters.");
        }
        if (repo.countByAdminId(admin.getId()) >= MAX_PASSCODES) {
            throw new IllegalArgumentException("You already have " + MAX_PASSCODES + " passcodes. Delete one to add another.");
        }
        for (AdminPasscode p : repo.findByAdminIdOrderByCreatedAtAsc(admin.getId())) {
            if (encoder.matches(code, p.getPasscodeHash())) {
                throw new IllegalArgumentException("You already use this passcode. Choose a different one.");
            }
        }
        String name = label == null ? "" : label.trim();
        if (name.isEmpty()) name = "Passcode";
        if (name.length() > 40) name = name.substring(0, 40);
        return repo.save(AdminPasscode.builder()
                .adminId(admin.getId())
                .label(name)
                .passcodeHash(encoder.encode(code))
                .build());
    }

    @Transactional
    public void delete(User admin, UUID id, String password) {
        requirePassword(admin, password);
        AdminPasscode p = repo.findByIdAndAdminId(id, admin.getId())
                .orElseThrow(() -> new IllegalArgumentException("Passcode not found."));
        repo.delete(p);
    }

    /** True when the passcode matches one of this admin's passcodes. Five wrong tries lock the address for 15 minutes. */
    @Transactional
    public boolean verifyForRecovery(String email, UUID adminIdOrNull, String passcode) {
        long now = System.currentTimeMillis();
        if (failures.size() > 5000) failures.clear();
        long[] st = failures.computeIfAbsent(email, k -> new long[]{0, now});
        synchronized (st) {
            if (now - st[1] > FAIL_WINDOW.toMillis()) { st[0] = 0; st[1] = now; }
            if (st[0] >= MAX_FAILURES) return false;
            boolean ok = false;
            String code = passcode == null ? "" : passcode.trim();
            if (adminIdOrNull != null && !code.isEmpty() && code.length() <= MAX_LEN) {
                for (AdminPasscode p : repo.findByAdminIdOrderByCreatedAtAsc(adminIdOrNull)) {
                    if (encoder.matches(code, p.getPasscodeHash())) {
                        p.setLastUsedAt(Instant.now());
                        repo.save(p);
                        ok = true;
                        break;
                    }
                }
            }
            if (ok) { failures.remove(email); return true; }
            st[0]++;
            return false;
        }
    }

    public String badMessage() { return BAD; }

    private void requirePassword(User admin, String password) {
        if (password == null || !encoder.matches(password, admin.getPasswordHash())) {
            throw new IllegalArgumentException("Your account password is not correct.");
        }
    }
}
