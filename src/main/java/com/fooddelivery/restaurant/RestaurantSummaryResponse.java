package com.fooddelivery.restaurant;

import com.fooddelivery.common.Ratings;

import java.math.BigDecimal;

/** Customer-facing view: no owner details, no admin flags. */
public record RestaurantSummaryResponse(Long id, String name, String address, String cuisine, boolean open,
                                        Long cityId, String cityName, BigDecimal averageRating, int ratingCount) {

    public static RestaurantSummaryResponse from(Restaurant r) {
        return new RestaurantSummaryResponse(r.getId(), r.getName(), r.getAddress(), r.getCuisine(), r.isOpen(),
                r.getCity().getId(), r.getCity().getName(),
                Ratings.average(r.getRatingSum(), r.getRatingCount()), r.getRatingCount());
    }
}
