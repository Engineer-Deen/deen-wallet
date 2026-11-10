package com.glr.deenwallet.monitoring;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers which visitor addresses a real, signed-in customer or admin has used. The automatic ban checks this list and
 * never blocks such an address, because many customers share one mobile-network address.
 * Only the address and the last time it was seen are kept (no name, no account), and rows older than 60 days are deleted.
 */
@Service
public class KnownClientIps {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void touch(String ip) {
        em.createNativeQuery("INSERT INTO known_client_ips (ip, last_seen) VALUES (?1, now()) "
                        + "ON CONFLICT (ip) DO UPDATE SET last_seen = now()")
                .setParameter(1, ip)
                .executeUpdate();
    }

    /** True when a signed-in customer or admin used this address within the last 30 days. */
    @Transactional(readOnly = true)
    public boolean isKnown(String ip) {
        return !em.createNativeQuery("SELECT 1 FROM known_client_ips WHERE ip = ?1 AND last_seen > now() - interval '30 days'")
                .setParameter(1, ip)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Scheduled(cron = "0 50 3 * * *")
    @Transactional
    public void cleanup() {
        em.createNativeQuery("DELETE FROM known_client_ips WHERE last_seen < now() - interval '60 days'").executeUpdate();
    }
}
