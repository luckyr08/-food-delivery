package com.fooddelivery.menu;

import java.math.BigDecimal;

/** Owner view: includes the raw stock number and the owner's own availability toggle. */
public record MenuItemOwnerResponse(Long id, String name, String description, String category, BigDecimal price,
                                    boolean veg, boolean available, Integer stock, boolean flashSale,
                                    boolean orderable) {

    public static MenuItemOwnerResponse from(MenuItem m) {
        return new MenuItemOwnerResponse(m.getId(), m.getName(), m.getDescription(), m.getCategory(), m.getPrice(),
                m.isVeg(), m.isAvailable(), m.getStock(), m.isFlashSale(), m.isOrderable());
    }
}
