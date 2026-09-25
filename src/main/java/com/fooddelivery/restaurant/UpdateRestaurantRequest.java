package com.fooddelivery.restaurant;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Admin PATCH: null = leave unchanged. Moving a restaurant to another city is not supported. */
public record UpdateRestaurantRequest(
        @Size(max = 150) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String address,
        @Size(max = 100) String cuisine,
        Boolean active) {

    public UpdateRestaurantRequest {
        name = name == null ? null : name.trim();
        address = address == null ? null : address.trim();
        cuisine = cuisine == null ? null : cuisine.trim();
    }
}
