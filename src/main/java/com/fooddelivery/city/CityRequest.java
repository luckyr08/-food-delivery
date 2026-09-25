package com.fooddelivery.city;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CityRequest(@NotBlank @Size(max = 100) String name, @Size(max = 100) String state) {

    public CityRequest {
        name = name == null ? null : name.trim();
        state = state == null || state.isBlank() ? null : state.trim();
    }
}
