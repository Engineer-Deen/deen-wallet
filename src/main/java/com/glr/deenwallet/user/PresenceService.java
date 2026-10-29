package com.glr.deenwallet.user;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Remembers when a customer was last seen and counts who is using the app right now. */
@Service
public class PresenceService {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void touch(UUID userId) {
        em.createNativeQuery("UPDATE users SET last_seen_at = now() WHERE id = :id")
                .setParameter("id", userId).executeUpdate();
    }

    /** Customers seen within the last few minutes. */
    @Transactional(readOnly = true)
    public long onlineCustomers(int minutes) {
        Object n = em.createNativeQuery(
                        "SELECT COUNT(*) FROM users WHERE role = 'USER' AND last_seen_at > now() - make_interval(mins => :m)")
                .setParameter("m", minutes).getSingleResult();
        return n == null ? 0L : ((Number) n).longValue();
    }
}
