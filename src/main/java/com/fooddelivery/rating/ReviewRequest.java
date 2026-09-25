package com.fooddelivery.rating;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Restaurant rating required; partner rating optional. */
public record ReviewRequest(
        @NotNull @Min(1) @Max(5) Integer restaurantRating,
        @Min(1) @Max(5) Integer partnerRating,
        @Size(max = 1000) String comment) {

    public ReviewRequest {
        comment = comment == null || comment.isBlank() ? null : comment.trim();
    }
}
