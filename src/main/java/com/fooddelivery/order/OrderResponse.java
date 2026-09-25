package com.fooddelivery.order;

import com.fooddelivery.payment.Payment;
import com.fooddelivery.payment.PaymentResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, OrderStatus status, Long restaurantId, String restaurantName,
                            List<OrderItemResponse> items, BigDecimal subtotal, BigDecimal deliveryFee,
                            BigDecimal totalAmount, String deliveryAddress, PaymentResponse payment,
                            Long deliveryPartnerId, Instant createdAt) {

    /** Expects restaurant and items loaded. */
    public static OrderResponse from(Order o, Payment payment) {
        return new OrderResponse(o.getId(), o.getStatus(), o.getRestaurant().getId(), o.getRestaurant().getName(),
                o.getItems().stream().map(OrderItemResponse::from).toList(),
                o.getSubtotal(), o.getDeliveryFee(), o.getTotalAmount(), o.getDeliveryAddress(),
                PaymentResponse.from(payment),
                o.getDeliveryPartner() == null ? null : o.getDeliveryPartner().getId(),
                o.getCreatedAt());
    }
}
