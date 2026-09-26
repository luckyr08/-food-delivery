package com.fooddelivery.search;

import java.math.BigDecimal;
import java.util.List;

public record SearchResultItem(long id, String name, String cuisine, String cityName, boolean open,
                               BigDecimal averageRating, int ratingCount, List<String> matchedDishes, Double score) {

    static SearchResultItem from(SearchHit hit) {
        RestaurantDocument d = hit.document();
        return new SearchResultItem(d.id(), d.name(), d.cuisine(), d.cityName(), d.open(), d.averageRating(),
                d.ratingCount(), hit.matchedDishes(), Math.round(hit.score() * 100) / 100.0);
    }
}
