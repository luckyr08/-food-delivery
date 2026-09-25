package com.fooddelivery.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Ratings are stored as sum + count (atomic increments); the average is derived on read. */
public final class Ratings {

    private Ratings() {
    }

    /** Average rounded to 1 decimal, or null when there are no ratings yet. */
    public static BigDecimal average(int sum, int count) {
        if (count == 0) {
            return null;
        }
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }
}
