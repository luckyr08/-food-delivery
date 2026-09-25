package com.fooddelivery.restaurant;

import com.fooddelivery.common.Ratings;

import java.math.BigDecimal;

public record RestaurantResponse(Long id, String name, String address, String cuisine, boolean open, boolean active,
                                 Long cityId, String cityName, Long ownerId, String ownerName,
                                 BigDecimal averageRating, int ratingCount) {

    /** Expects owner and city to be loaded (entity graph), otherwise this triggers lazy queries. */
    public static RestaurantResponse from(Restaurant r) {
        return new RestaurantResponse(r.getId(), r.getName(), r.getAddress(), r.getCuisine(), r.isOpen(), r.isActive(),
                r.getCity().getId(), r.getCity().getName(), r.getOwner().getId(), r.getOwner().getName(),
                Ratings.average(r.getRatingSum(), r.getRatingCount()), r.getRatingCount());
    }
}
