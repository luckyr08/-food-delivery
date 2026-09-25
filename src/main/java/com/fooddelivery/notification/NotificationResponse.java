package com.fooddelivery.notification;

import java.time.Instant;

public record NotificationResponse(Long id, NotificationType type, Long orderId, String message, boolean read,
                                   Instant createdAt) {

    static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getOrder() == null ? null : n.getOrder().getId(),
                n.getMessage(), n.isRead(), n.getCreatedAt());
    }
}
