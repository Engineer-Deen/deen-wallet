package com.glr.deenwallet.notification;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends push notifications via FCM to every device a user has registered.
 * Call this from wherever something happens that a user should be told
 * about even when the app isn't open - e.g. a transaction completing, an
 * admin locking their account, a new OTP being sent.
 *
 * Usage example (from another service):
 *   pushNotificationService.sendToUser(userId, "Transfer complete",
 *       "Your transfer of SLE 500 to 0803xxxx was successful.",
 *       Map.of("type", "TRANSACTION_COMPLETED", "transactionId", tx.getId().toString()));
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final DeviceTokenRepository deviceTokenRepository;

    public void sendToUser(UUID userId, String title, String body, Map<String, String> data) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("Push notification skipped (Firebase not configured): {} - {}", title, body);
            return;
        }

        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        for (DeviceToken deviceToken : tokens) {
            send(deviceToken, title, body, data);
        }
    }

    private void send(DeviceToken deviceToken, String title, String body, Map<String, String> data) {
        Message.Builder messageBuilder = Message.builder()
                .setToken(deviceToken.getFcmToken())
                .setNotification(Notification.builder().setTitle(title).setBody(body).build());
        if (data != null) {
            messageBuilder.putAllData(data);
        }

        try {
            FirebaseMessaging.getInstance().send(messageBuilder.build());
        } catch (FirebaseMessagingException e) {
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                // Token is dead (app uninstalled, token rotated, etc.) - stop
                // trying to send to it.
                deviceTokenRepository.deleteByFcmToken(deviceToken.getFcmToken());
                log.info("Removed dead device token for user {}", deviceToken.getUserId());
            } else {
                log.warn("Failed to send push notification to user {}: {}", deviceToken.getUserId(), e.getMessage());
            }
        }
    }
}
