package com.fooddelivery.payment;

public enum PaymentStatus {
    /** Online payment created with the order; the gateway call happens after commit. */
    INITIATED,
    /** Cash on delivery: collected when the order is delivered. */
    PENDING,
    SUCCESS,
    FAILED,
    /** Refund requested in the cancelling transaction; the outbox relay calls the gateway after commit. */
    REFUND_PENDING,
    REFUNDED,
    /** Cash-on-delivery order cancelled before delivery: no money ever moved. */
    VOIDED
}
