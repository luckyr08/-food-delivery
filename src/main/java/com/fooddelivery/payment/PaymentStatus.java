package com.fooddelivery.payment;

public enum PaymentStatus {
    /** Cash on delivery: collected when the order is delivered. */
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED
}
