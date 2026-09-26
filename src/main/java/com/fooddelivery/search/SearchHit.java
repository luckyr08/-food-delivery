package com.fooddelivery.search;

import java.util.List;

/** A restaurant hit plus the dishes that matched the text (ES nested inner_hits). */
public record SearchHit(RestaurantDocument document, double score, List<String> matchedDishes) {
}
