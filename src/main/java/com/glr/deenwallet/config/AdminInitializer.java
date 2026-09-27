package com.glr.deenwallet.config;

import com.glr.deenwallet.user.AccountNumberGenerator;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer {
    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final AccountNumberGenerator accounts;

    @Value("${app.admin.email:}") private String email;
    @Value("${app.admin.password:}") private String password;
    @Value("${app.admin.phone:}") private String phone;

    // SECURITY: Off by default. This must only be set to true for a single,
    // deliberate restart when an operator genuinely needs to recover a locked
    // out / disabled super-admin account outside the normal email-OTP recovery
    // flow. Leaving this permanently "true" in the environment recreates the
    // exact skeleton-key problem this initializer used to have: it would
    // silently repair the password hash and clear locked/recovery/active
    // state on *every* startup, which meant anyone who knew ADMIN_PASSWORD
    // could always undo an intentional account lockout. Set it to true,
    // restart once, then set it back to false (or remove it).
    @Value("${app.admin.force-reset:false}") private boolean forceReset;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.info("Admin bootstrap skipped; set ADMIN_EMAIL and ADMIN_PASSWORD only when bootstrapping a new admin.");
            return;
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        repo.findByEmail(normalizedEmail).ifPresentOrElse(user -> {
            if (!forceReset) {
                // The account already exists. Do nothing further - in
                // particular, never touch the password hash or the
                // locked/active/recovery flags just because the app restarted.
                // Use the email-OTP recovery flow, or restart once with
                // ADMIN_FORCE_RESET=true, to intentionally recover this account.
                return;
            }

            log.warn("ADMIN_FORCE_RESET is enabled - resetting password and clearing lock/recovery state for {}. " +
                    "Turn this flag off after this restart.", normalizedEmail);

            boolean changed = false;
            if (!"SUPER_ADMIN".equals(user.getRole())) {
                user.setRole("SUPER_ADMIN");
                changed = true;
            }
            if (!encoder.matches(password, user.getPasswordHash())) {
                user.setPasswordHash(encoder.encode(password));
                changed = true;
            }
            if (user.isLocked() || user.getLoginFailedAttempts() > 0 || user.isAdminRecoveryRequired() || user.getPinAttempts() > 0 || !user.isActive()) {
                user.setLocked(false);
                user.setLockedAt(null);
                user.setLockedBy(null);
                user.setLoginFailedAttempts(0);
                user.setLoginLockedUntil(null);
                user.setLastLoginFailedAt(null);
                user.setAdminRecoveryRequired(false);
                user.setPinAttempts(0);
                user.setActive(true);
                changed = true;
            }
            if (changed) {
                repo.save(user);
            }
        }, () -> {
            if (phone == null || !phone.matches("^\\+232\\d{8}$")) {
                throw new IllegalStateException("ADMIN_PHONE is required in +232XXXXXXXX format when creating the initial super admin.");
            }

            User admin = User.builder()
                    .email(normalizedEmail)
                    .passwordHash(encoder.encode(password))
                    .fullName("Deen Wallet Administrator")
                    .username("superadmin")
                    .accountNumber(accounts.generate())
                    .active(true)
                    .emailVerified(true)
                    .locked(false)
                    .phone(phone)
                    .pinHash("")
                    .phoneVerified(false)
                    .role("SUPER_ADMIN")
                    .build();

            repo.save(admin);
            log.info("Super admin created for configured email");
        });
    }
}

