package com.fooddelivery.menu;

import java.math.BigDecimal;

/**
 * Customer view. Exact stock is business-sensitive, so only a computed "available" flag is exposed;
 * sold-out items are still listed (greyed out in the app).
 */
public record MenuItemResponse(Long id, String name, String description, String category, BigDecimal price,
                               boolean veg, boolean available) {

    public static MenuItemResponse from(MenuItem m) {
        return new MenuItemResponse(m.getId(), m.getName(), m.getDescription(), m.getCategory(), m.getPrice(),
                m.isVeg(), m.isOrderable());
    }
}
