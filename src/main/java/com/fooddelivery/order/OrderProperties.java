package com.fooddelivery.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "app.order")
public record OrderProperties(@NotNull @PositiveOrZero BigDecimal deliveryFee,
                              @NotNull @PositiveOrZero BigDecimal freeDeliveryFrom) {
}
