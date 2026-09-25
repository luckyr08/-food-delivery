package com.fooddelivery.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Owner moves an order: ACCEPTED, REJECTED (reason required), PREPARING, READY_FOR_PICKUP. */
public record OrderStatusUpdateRequest(@NotNull OrderStatus status, @Size(max = 255) String reason) {
}
