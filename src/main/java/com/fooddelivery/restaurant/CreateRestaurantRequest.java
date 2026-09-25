package com.fooddelivery.restaurant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRestaurantRequest(
        @NotNull Long ownerId,
        @NotNull Long cityId,
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String address,
        @Size(max = 100) String cuisine) {

    public CreateRestaurantRequest {
        name = name == null ? null : name.trim();
        address = address == null ? null : address.trim();
        cuisine = cuisine == null || cuisine.isBlank() ? null : cuisine.trim();
    }
}
