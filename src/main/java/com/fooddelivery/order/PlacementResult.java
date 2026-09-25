package com.fooddelivery.order;

/** replayed = true when an Idempotency-Key matched an earlier order (nothing new was created). */
public record PlacementResult(OrderResponse order, boolean replayed) {
}
