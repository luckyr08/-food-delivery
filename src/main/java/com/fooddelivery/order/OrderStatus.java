package com.fooddelivery.order;

/** Lifecycle states. Allowed transitions are added with the state machine (step 7). */
public enum OrderStatus {
    PLACED,
    ACCEPTED,
    REJECTED,
    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED
}
