package com.fooddelivery.order;

import jakarta.validation.constraints.Size;

/** Reason is optional for customers and required for admins (checked in the service). */
public record CancelOrderRequest(@Size(max = 255) String reason) {
}
