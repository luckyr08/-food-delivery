package com.fooddelivery.order;

/** Lifecycle states. Allowed transitions and who may perform them: {@link OrderStateMachine}. */
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
