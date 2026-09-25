package com.fooddelivery.order;

import java.math.BigDecimal;

public record OrderItemResponse(Long menuItemId, String name, BigDecimal unitPrice, int quantity,
                                BigDecimal lineTotal) {

    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.getMenuItem().getId(), item.getItemName(), item.getUnitPrice(),
                item.getQuantity(), item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }
}
