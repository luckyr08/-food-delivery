package com.fooddelivery.menu;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Absolute stock: {"stock": 25} = 25 left, {"stock": null} = unlimited.
 * The field is required so an empty body {} can't silently mean "unlimited".
 */
public record StockRequest(@JsonProperty(required = true) @PositiveOrZero Integer stock) {
}
