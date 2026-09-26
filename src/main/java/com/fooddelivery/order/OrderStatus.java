package com.fooddelivery.order;

/** Lifecycle states. Allowed transitions and who may perform them: {@link OrderStateMachine}. */
public enum OrderStatus {
    /** Stock reserved, payment in progress. Not visible to the restaurant yet. */
    PAYMENT_PENDING,
    PLACED,
    ACCEPTED,
    REJECTED,
    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED
}
