package com.fooddelivery.search;

import java.math.BigDecimal;

/** Nested inside RestaurantDocument (ES "nested" type). No stock: it changes on every order. */
public record MenuDocument(long id, String name, String category, BigDecimal price, boolean veg, boolean available) {
}
