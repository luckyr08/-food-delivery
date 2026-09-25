package com.fooddelivery.menu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MenuItemTest {

    private static MenuItem item(boolean active, boolean available, Integer stock) {
        MenuItem m = new MenuItem();
        m.setActive(active);
        m.setAvailable(available);
        m.setStock(stock);
        return m;
    }

    @Test
    void unlimitedStockIsOrderable() {
        assertThat(item(true, true, null).isOrderable()).isTrue();
    }

    @Test
    void positiveStockIsOrderable() {
        assertThat(item(true, true, 1).isOrderable()).isTrue();
    }

    @Test
    void zeroStockIsNotOrderable() {
        assertThat(item(true, true, 0).isOrderable()).isFalse();
    }

    @Test
    void ownerToggleOffIsNotOrderable() {
        assertThat(item(true, false, null).isOrderable()).isFalse();
    }

    @Test
    void deletedItemIsNotOrderable() {
        assertThat(item(false, true, null).isOrderable()).isFalse();
    }
}
