package com.fooddelivery.notification;

/** External channel (push / SMS / email). Production would plug in Firebase, Twilio, SES, ... */
public interface NotificationSender {

    void send(Long userId, String message);
}
