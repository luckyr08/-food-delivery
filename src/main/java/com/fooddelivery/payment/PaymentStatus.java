package com.fooddelivery.payment;

public enum PaymentStatus {
    /** Cash on delivery: collected when the order is delivered. */
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED,
    /** Cash-on-delivery order cancelled before delivery: no money ever moved. */
    VOIDED
}
