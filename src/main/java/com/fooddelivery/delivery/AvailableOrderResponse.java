package com.fooddelivery.delivery;

import com.fooddelivery.order.Order;
import com.fooddelivery.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** What a partner needs to decide whether to claim: pickup, drop-off, value. */
public record AvailableOrderResponse(Long orderId, OrderStatus status, String restaurantName, String pickupAddress,
                                     String deliveryAddress, BigDecimal totalAmount, Instant placedAt) {

    static AvailableOrderResponse from(Order o) {
        return new AvailableOrderResponse(o.getId(), o.getStatus(), o.getRestaurant().getName(),
                o.getRestaurant().getAddress(), o.getDeliveryAddress(), o.getTotalAmount(), o.getCreatedAt());
    }
}
