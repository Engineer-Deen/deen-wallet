package com.glr.deenwallet.notification;

import com.glr.deenwallet.notification.DeviceToken;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
import com.google.firebase.messaging.WebpushNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * A push problem must never fail the action that triggered it (the in-app
 * notification or transaction is already saved by then), so every failure
 * here is logged and swallowed.
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

    // Shown next to web notifications (Chrome, Edge, Firefox). Must be a public https
    // address. The 192px icon is small and quick to load on a slow connection.
    @Value("${app.push.icon-url:https://api.deenwallapp.com/assets/icon-192.png}")
    private String iconUrl;

    // Where a tap on a web notification opens. Must be https.
    @Value("${app.push.click-url:https://api.deenwallapp.com/index.html}")
    private String clickUrl;

    // Must match the channel the Android app creates (see deenwallet-client.js).
    private static final String ANDROID_CHANNEL_ID = "deenwallet_alerts";

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
        try {
            Message.Builder messageBuilder = Message.builder()
                    .setToken(deviceToken.getFcmToken())
                    .setNotification(
                            Notification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .build()
                    )
                    // Android: high priority, with the app's alert channel (sound + vibration).
                    .setAndroidConfig(
                            AndroidConfig.builder()
                                    .setPriority(AndroidConfig.Priority.HIGH)
                                    .setNotification(
                                            AndroidNotification.builder()
                                                    .setChannelId(ANDROID_CHANNEL_ID)
                                                    .setSound("default")
                                                    .setDefaultVibrateTimings(true)
                                                    .build()
                                    )
                                    .build()
                    )
                    // Web: the DeenWallet logo, and a tap opens the app.
                    .setWebpushConfig(buildWebpushConfig(title, body));

            if (data != null) {
                messageBuilder.putAllData(data);
            }

            FirebaseMessaging.getInstance().send(messageBuilder.build());

        } catch (FirebaseMessagingException e) {
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {

                // Token is dead (app uninstalled, token rotated, etc.) - stop
                // trying to send to it.
                removeDeadToken(deviceToken);

            } else {
                log.warn(
                        "Failed to send push notification to user {}: {}",
                        deviceToken.getUserId(),
                        e.getMessage()
                );
            }

        } catch (RuntimeException e) {
            log.warn(
                    "Unexpected error sending push notification to user {}: {}",
                    deviceToken.getUserId(),
                    e.getMessage(),
                    e
            );
        }
    }

    private WebpushConfig buildWebpushConfig(String title, String body) {
        WebpushConfig.Builder web = WebpushConfig.builder()
                .setNotification(
                        WebpushNotification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .setIcon(iconUrl)
                                .setBadge(iconUrl)
                                .build()
                );

        // FCM only accepts an https click address.
        if (clickUrl != null && clickUrl.startsWith("https://")) {
            web.setFcmOptions(WebpushFcmOptions.withLink(clickUrl));
        }

        return web.build();
    }

    private void removeDeadToken(DeviceToken deviceToken) {
        try {
            deviceTokenRepository.deleteByFcmToken(deviceToken.getFcmToken());
            log.info("Removed dead device token for user {}", deviceToken.getUserId());

        } catch (RuntimeException e) {
            log.warn(
                    "Could not remove dead device token for user {}: {}",
                    deviceToken.getUserId(),
                    e.getMessage()
            );
        }
    }
}