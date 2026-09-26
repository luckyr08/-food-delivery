package com.fooddelivery.payment;

import java.math.BigDecimal;

/**
 * Gateway notification. type: payment.captured | payment.failed | refund.processed.
 * orderReference = our order id (sent as the charge reference); providerRef = the gateway's payment id.
 */
public record WebhookEvent(String eventId, String type, String orderReference, String providerRef,
                           BigDecimal amount, String reason) {
}
