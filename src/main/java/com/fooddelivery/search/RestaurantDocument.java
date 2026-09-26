package com.fooddelivery.search;

import java.math.BigDecimal;
import java.util.List;

/**
 * One search document per publicly visible restaurant, with its menu nested (the shape of the
 * `restaurants` index, see resources/search/restaurants-mapping.json).
 * version = restaurants.search_version (external versioning).
 */
public record RestaurantDocument(long id, String name, String address, String cuisine, long cityId, String cityName,
                                 boolean open, BigDecimal averageRating, int ratingCount, List<MenuDocument> menu,
                                 long version) {
}
