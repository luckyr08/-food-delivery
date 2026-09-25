package com.fooddelivery.city;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PATCH semantics: null = leave unchanged. */
public record CityUpdateRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 100) String state,
        Boolean active) {

    public CityUpdateRequest {
        name = name == null ? null : name.trim();
        state = state == null ? null : state.trim();
    }
}
