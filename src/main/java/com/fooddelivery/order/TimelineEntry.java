package com.fooddelivery.order;

import java.time.Instant;

/** One step of the customer's tracking timeline. */
public record TimelineEntry(OrderStatus status, Instant at, String note) {

    static TimelineEntry from(OrderStatusHistory h) {
        return new TimelineEntry(h.getToStatus(), h.getChangedAt(), h.getNote());
    }
}
