package com.fooddelivery.menu;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** PATCH: null = unchanged. Stock is NOT here: null would be ambiguous (unchanged vs unlimited). */
public record MenuItemUpdateRequest(
        @Size(max = 150) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 500) String description,
        @Size(max = 100) String category,
        @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        Boolean veg,
        Boolean available) {

    public MenuItemUpdateRequest {
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
        category = category == null ? null : category.trim();
    }
}
