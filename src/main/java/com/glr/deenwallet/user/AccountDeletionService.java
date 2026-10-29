package com.glr.deenwallet.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deletes an account the safe way. The person disappears (name, email, phone, username, sign-in, devices, saved
 * recipients, notifications are removed or blanked and the account can never sign in again), but the money records stay:
 * transactions and KYC records are kept for the books, now shown as "Deleted account".
 * The email address and phone number become free to register again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    private static final String[] OWNED_BY_USER_ID = {
            "refresh_tokens", "device_tokens", "notifications", "password_reset_tokens",
            "biometric_credentials", "biometric_challenges", "pin_reset_challenges", "saved_recipients"
    };

    /** Why this account cannot be deleted right now, or null when it can. */
    @Transactional(readOnly = true)
    public String blocker(User u) {
        long open = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM transactions WHERE user_id = :id AND (status IN "
                                + "('PAID_IN','PAYING_OUT','PAYOUT_FAILED','REFUND_PENDING','NEEDS_REVIEW') "
                                + "OR (status = 'AWAITING_PAYMENT' AND created_at > now() - interval '2 hours'))")
                .setParameter("id", u.getId()).getSingleResult()).longValue();
        if (open > 0) return "A transfer is still in progress on this account. Try again when it has finished.";
        if ("ADMIN".equals(u.getRole()) || "SUPER_ADMIN".equals(u.getRole())) {
            long wd = ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM wallet_withdrawals WHERE owner_user_id = :id AND status IN ('PENDING','REVIEW')")
                    .setParameter("id", u.getId()).getSingleResult()).longValue();
            if (wd > 0) return "This admin has a wallet withdrawal that is not finished yet. Try again when it has completed.";
        }
        return null;
    }

    @Transactional
    public void delete(UUID id) {
        User u = users.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if ("DELETED".equals(u.getRole())) return;
        if ("SUPER_ADMIN".equals(u.getRole()) && u.isMainAdmin()) {
            throw new IllegalArgumentException("The main super admin cannot be deleted.");
        }
        String why = blocker(u);
        if (why != null) throw new IllegalArgumentException(why);

        String oldEmail = u.getEmail();
        for (String table : OWNED_BY_USER_ID) {
            em.createNativeQuery("DELETE FROM " + table + " WHERE user_id = :id").setParameter("id", id).executeUpdate();
        }
        em.createNativeQuery("DELETE FROM admin_saved_accounts WHERE owner_user_id = :id").setParameter("id", id).executeUpdate();
        if (oldEmail != null) {
            em.createNativeQuery("DELETE FROM email_otps WHERE email = :e").setParameter("e", oldEmail).executeUpdate();
        }

        String tag = id.toString().replace("-", "").substring(0, 12);
        u.setFullName("Deleted account");
        u.setUsername("deleted_" + tag);
        u.setPhone("del-" + tag);
        u.setEmail("deleted-" + tag + "@deleted.invalid");
        u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        u.setPinHash("");
        u.setActive(false);
        u.setLocked(false);
        u.setEmailVerified(false);
        u.setPhoneVerified(false);
        u.setMainAdmin(false);
        u.setDeniedSections("");
        u.setRole("DELETED");
        users.save(u);
        log.info("Account {} was deleted", id);
    }
}
