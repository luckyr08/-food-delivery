package com.fooddelivery.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Pure pricing rules, easy to unit test. BigDecimal throughout: no floating-point money. */
public final class OrderPricing {

    private OrderPricing() {
    }

    public record Line(BigDecimal unitPrice, int quantity) {
    }

    public record Totals(BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total) {
    }

    public static Totals calculate(List<Line> lines, OrderProperties props) {
        BigDecimal subtotal = lines.stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = subtotal.compareTo(props.freeDeliveryFrom()) >= 0
                ? BigDecimal.ZERO.setScale(2)
                : props.deliveryFee().setScale(2, RoundingMode.HALF_UP);
        return new Totals(subtotal, fee, subtotal.add(fee));
    }
}
