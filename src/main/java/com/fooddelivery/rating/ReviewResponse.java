package com.fooddelivery.rating;

import java.time.Instant;

/** The customer's own view of their review. */
public record ReviewResponse(Long id, Long orderId, int restaurantRating, Integer partnerRating, String comment,
                             Instant createdAt) {

    static ReviewResponse from(Review r) {
        return new ReviewResponse(r.getId(), r.getOrder().getId(), r.getRestaurantRating(), r.getPartnerRating(),
                r.getComment(), r.getCreatedAt());
    }
}
