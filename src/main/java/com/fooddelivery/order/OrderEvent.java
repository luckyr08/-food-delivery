package com.fooddelivery.order;

import java.time.Instant;

/**
 * Published inside the order transaction, delivered to listeners only after it commits.
 * Carries ids and plain values, never JPA entities: listeners run on another thread after the
 * persistence context is closed, where lazy entity fields would throw.
 */
public record OrderEvent(Kind kind, Long orderId, OrderStatus fromStatus, OrderStatus toStatus,
                         Long customerId, Long restaurantOwnerId, Long partnerUserId, String restaurantName,
                         Long actorUserId, String reason, Instant occurredAt) {

    public enum Kind {
        STATUS_CHANGED,
        PARTNER_ASSIGNED
    }

    /** Reads only ids of lazy associations (available on Hibernate proxies without a query). */
    public static OrderEvent of(Kind kind, Order order, OrderStatus from, Long actorUserId, String reason) {
        Long partnerUserId = order.getDeliveryPartner() == null ? null : order.getDeliveryPartner().getUser().getId();
        return new OrderEvent(kind, order.getId(), from, order.getStatus(), order.getCustomer().getId(),
                order.getRestaurant().getOwner().getId(), partnerUserId, order.getRestaurant().getName(),
                actorUserId, reason, Instant.now());
    }
}
