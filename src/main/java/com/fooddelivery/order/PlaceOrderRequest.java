package com.fooddelivery.order;

import com.fooddelivery.payment.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Deliberately has no prices: the server always prices from the database. */
public record PlaceOrderRequest(
        @NotNull Long restaurantId,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid OrderLineRequest> items,
        @NotBlank @Size(min = 5, max = 500) String deliveryAddress,
        @NotNull PaymentMethod paymentMethod) {

    public PlaceOrderRequest {
        deliveryAddress = deliveryAddress == null ? null : deliveryAddress.trim();
    }
}
