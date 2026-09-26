package com.fooddelivery.menu;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Create a menu item. stock null = unlimited; veg/available default to true; flashSale defaults to false. */
public record MenuItemRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        @Size(max = 100) String category,
        // Matches DECIMAL(10,2): up to 8 integer digits, 2 decimals.
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        Boolean veg,
        Boolean available,
        @PositiveOrZero Integer stock,
        Boolean flashSale) {

    public MenuItemRequest {
        name = name == null ? null : name.trim();
        description = description == null || description.isBlank() ? null : description.trim();
        category = category == null || category.isBlank() ? null : category.trim();
    }
}
