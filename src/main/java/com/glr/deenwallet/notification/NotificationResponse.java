package com.glr.deenwallet.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String message,
        UUID transactionId,
        String transactionReference,
        boolean read,
        Instant createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getTransactionId(),
                notification.getTransactionReference(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}