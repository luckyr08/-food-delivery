package com.fooddelivery.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(Long userId, String message) {
        log.info("[push] user {}: {}", userId, message);
    }
}
