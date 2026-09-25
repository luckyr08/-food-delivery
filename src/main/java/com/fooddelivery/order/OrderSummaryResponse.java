package com.fooddelivery.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(Long id, OrderStatus status, Long restaurantId, String restaurantName,
                                   BigDecimal totalAmount, Instant createdAt) {

    static OrderSummaryResponse from(Order o) {
        return new OrderSummaryResponse(o.getId(), o.getStatus(), o.getRestaurant().getId(),
                o.getRestaurant().getName(), o.getTotalAmount(), o.getCreatedAt());
    }
}
