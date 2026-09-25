package com.fooddelivery.order;

import com.fooddelivery.payment.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHashTest {

    private static PlaceOrderRequest request(List<OrderLineRequest> lines) {
        return new PlaceOrderRequest(1L, lines, "Flat 4B, MG Road", PaymentMethod.UPI);
    }

    @Test
    void lineOrderDoesNotMatter() {
        var a = request(List.of(new OrderLineRequest(1L, 2), new OrderLineRequest(5L, 1)));
        var b = request(List.of(new OrderLineRequest(5L, 1), new OrderLineRequest(1L, 2)));

        assertThat(RequestHash.of(a)).isEqualTo(RequestHash.of(b)).hasSize(64);
    }

    @Test
    void differentQuantityChangesHash() {
        var a = request(List.of(new OrderLineRequest(1L, 2)));
        var b = request(List.of(new OrderLineRequest(1L, 3)));

        assertThat(RequestHash.of(a)).isNotEqualTo(RequestHash.of(b));
    }
}
