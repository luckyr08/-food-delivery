package com.fooddelivery.notification;

import com.fooddelivery.common.config.AsyncConfig;
import com.fooddelivery.order.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans order events out to the people involved.
 * AFTER_COMMIT: runs only if the order transaction committed (no notices about rolled-back orders).
 * @Async:       runs on the notification pool, so the HTTP request never waits for it.
 * Best effort: an in-memory event is lost if the app crashes before this runs; a transactional
 * outbox is the production answer (see ADR 0011).
 */
@Slf4j
@Component
public class OrderNotificationListener {

    private final NotificationService notificationService;
    private final NotificationSender sender;

    public OrderNotificationListener(NotificationService notificationService, NotificationSender sender) {
        this.notificationService = notificationService;
        this.sender = sender;
    }

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderEvent(OrderEvent event) {
        NotificationType type = NotificationRules.type(event);
        for (Recipient recipient : NotificationRules.recipients(event)) {
            // Isolate each recipient: one failure must not stop the rest of the fan-out.
            try {
                String message = NotificationRules.message(event, recipient);
                notificationService.record(recipient.userId(), event.orderId(), type, message);
                sender.send(recipient.userId(), message);
            } catch (Exception e) {
                log.error("Notification for order {} to user {} failed", event.orderId(), recipient.userId(), e);
            }
        }
    }
}
