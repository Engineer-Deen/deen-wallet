package com.glr.deenwallet.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells the admins (admin and super admin accounts) about something that happened, using the same notification
 * list and push sender the customers get. It never slows down or fails the action that triggered it: the work runs
 * on its own thread and every problem is only logged.
 */
@Service
public class AdminAlertService {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertService.class);
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "admin-alerts");
        t.setDaemon(true);
        return t;
    });

    @PersistenceContext
    private EntityManager em;

    private final NotificationService notifications;
    private final PushNotificationService push;

    public AdminAlertService(NotificationService notifications, PushNotificationService push) {
        this.notifications = notifications;
        this.push = push;
    }

    /** Notifies every active admin and super admin, except {@code exceptUserId} (the person who did it) when given. */
    public void notifyAdmins(UUID exceptUserId, String type, String title, String message) {
        try {
            POOL.execute(() -> run(exceptUserId, type, title, message));
        } catch (RuntimeException e) {
            log.warn("Admin alert could not be queued: {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void run(UUID exceptUserId, String type, String title, String message) {
        try {
            List<Object> ids = em.createNativeQuery(
                    "SELECT id FROM users WHERE role IN ('ADMIN', 'SUPER_ADMIN') AND active = TRUE").getResultList();
            for (Object o : ids) {
                UUID adminId = o instanceof UUID u ? u : UUID.fromString(String.valueOf(o));
                if (adminId.equals(exceptUserId)) continue;
                try {
                    NotificationResponse n = notifications.create(adminId, type, title, message, null, null);
                    push.sendToUser(adminId, title, message, Map.of("type", type, "notificationId", n.id().toString()));
                } catch (RuntimeException e) {
                    log.warn("Admin alert for {} failed: {}", adminId, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Admin alert '{}' failed: {}", type, e.toString());
        }
    }
}
