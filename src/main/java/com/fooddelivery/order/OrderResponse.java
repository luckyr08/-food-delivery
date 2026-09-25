package com.fooddelivery.order;

import com.fooddelivery.delivery.DeliveryPartner;
import com.fooddelivery.delivery.VehicleType;
import com.fooddelivery.payment.Payment;
import com.fooddelivery.payment.PaymentResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, OrderStatus status, Long restaurantId, String restaurantName,
                            List<OrderItemResponse> items, BigDecimal subtotal, BigDecimal deliveryFee,
                            BigDecimal totalAmount, String deliveryAddress, PaymentResponse payment,
                            AssignedPartner deliveryPartner, Instant createdAt) {

    /** Shown to the customer once a partner has claimed the order. */
    public record AssignedPartner(Long id, String name, String phone, VehicleType vehicleType) {

        static AssignedPartner from(DeliveryPartner p) {
            return p == null ? null
                    : new AssignedPartner(p.getId(), p.getUser().getName(), p.getUser().getPhone(), p.getVehicleType());
        }
    }

    /** Expects restaurant and items loaded. */
    public static OrderResponse from(Order o, Payment payment) {
        return new OrderResponse(o.getId(), o.getStatus(), o.getRestaurant().getId(), o.getRestaurant().getName(),
                o.getItems().stream().map(OrderItemResponse::from).toList(),
                o.getSubtotal(), o.getDeliveryFee(), o.getTotalAmount(), o.getDeliveryAddress(),
                PaymentResponse.from(payment),
                AssignedPartner.from(o.getDeliveryPartner()),
                o.getCreatedAt());
    }
}
