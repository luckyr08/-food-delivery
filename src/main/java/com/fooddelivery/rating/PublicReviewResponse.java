package com.fooddelivery.rating;

import java.time.Instant;

/**
 * What everyone sees on a restaurant page: first name only, no partner rating
 * (partner feedback is for the platform, shown only as the partner's own average).
 */
public record PublicReviewResponse(String reviewer, int rating, String comment, Instant createdAt) {

    static PublicReviewResponse from(Review r) {
        String name = r.getCustomer().getName().trim();
        int space = name.indexOf(' ');
        return new PublicReviewResponse(space < 0 ? name : name.substring(0, space), r.getRestaurantRating(),
                r.getComment(), r.getCreatedAt());
    }
}
