package com.fooddelivery.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RatingsTest {

    @Test
    void noRatingsYieldsNull() {
        assertThat(Ratings.average(0, 0)).isNull();
    }

    @Test
    void roundsHalfUpToOneDecimal() {
        assertThat(Ratings.average(14, 3)).isEqualByComparingTo("4.7"); // 4.666...
        assertThat(Ratings.average(9, 2)).isEqualByComparingTo("4.5");
    }
}
