package com.fooddelivery.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderLineRequest(@NotNull Long menuItemId, @NotNull @Min(1) @Max(20) Integer quantity) {
}
