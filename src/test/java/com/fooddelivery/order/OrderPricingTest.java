package com.fooddelivery.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPricingTest {

    private final OrderProperties props = new OrderProperties(new BigDecimal("40"), new BigDecimal("500"));

    @Test
    void addsDeliveryFeeBelowThreshold() {
        var totals = OrderPricing.calculate(List.of(
                new OrderPricing.Line(new BigDecimal("149.50"), 2),
                new OrderPricing.Line(new BigDecimal("99.99"), 1)), props);

        assertThat(totals.subtotal()).isEqualByComparingTo("398.99");
        assertThat(totals.deliveryFee()).isEqualByComparingTo("40.00");
        assertThat(totals.total()).isEqualByComparingTo("438.99");
    }

    @Test
    void freeDeliveryAtExactlyTheThreshold() {
        var totals = OrderPricing.calculate(List.of(new OrderPricing.Line(new BigDecimal("250.00"), 2)), props);

        assertThat(totals.deliveryFee()).isEqualByComparingTo("0");
        assertThat(totals.total()).isEqualByComparingTo("500.00");
    }

    @Test
    void noFloatingPointDrift() {
        // 0.1 + 0.2 != 0.3 with double; BigDecimal keeps it exact.
        var totals = OrderPricing.calculate(List.of(
                new OrderPricing.Line(new BigDecimal("0.10"), 1),
                new OrderPricing.Line(new BigDecimal("0.20"), 1)), props);

        assertThat(totals.subtotal()).isEqualByComparingTo("0.30");
    }
}
