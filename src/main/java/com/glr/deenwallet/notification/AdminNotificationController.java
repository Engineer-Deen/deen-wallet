package com.glr.deenwallet.notification;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The admin's own notifications (what customers see in their app, for admin and super admin accounts) and the
 * registration of the admin phone for push alerts. Same storage and sender as the customer ones.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminNotificationController {

    private final NotificationService notifications;
    private final DeviceTokenRepository deviceTokens;

    public AdminNotificationController(NotificationService notifications, DeviceTokenRepository deviceTokens) {
        this.notifications = notifications;
        this.deviceTokens = deviceTokens;
    }

    @GetMapping("/my-notifications")
    public ResponseEntity<List<NotificationResponse>> list() {
        return ResponseEntity.ok(notifications.getForUser(me()));
    }

    @GetMapping("/my-notifications/unread-count")
    public ResponseEntity<Long> unread() {
        return ResponseEntity.ok(notifications.getUnreadCount(me()));
    }

    @PutMapping("/my-notifications/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        notifications.markAsRead(me(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/my-notifications/read-all")
    public ResponseEntity<Void> readAll() {
        notifications.markAllAsRead(me());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/device-token")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterDeviceTokenRequest request) {
        DeviceToken token = deviceTokens.findByFcmToken(request.fcmToken()).orElseGet(DeviceToken::new);
        token.setUserId(me());
        token.setFcmToken(request.fcmToken());
        token.setPlatform(request.platform());
        deviceTokens.save(token);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/device-token")
    public ResponseEntity<Void> unregister(@Valid @RequestBody RegisterDeviceTokenRequest request) {
        deviceTokens.deleteByUserIdAndFcmToken(me(), request.fcmToken());
        return ResponseEntity.noContent().build();
    }

    private static UUID me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Not authenticated");
        return UUID.fromString(String.valueOf(auth.getPrincipal()));
    }
}
