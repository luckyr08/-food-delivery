package com.fooddelivery.stockgate;

import com.fooddelivery.support.ConcurrencyTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** app.stock-gate.mode=redis: flash-sale losers are rejected by the gate and never reach MySQL. */
@TestPropertySource(properties = {"app.stock-gate.mode=redis", "app.stock-gate.max-units-per-user=2"})
class FlashSaleGateConcurrencyTest extends ConcurrencyTestBase {

    @Autowired
    SimulatedRedisStockGate gate;

    @BeforeEach
    void resetGate() {
        gate.reset();
    }

    private Long insertHotItem(int stock) {
        return insert("INSERT INTO menu_items (restaurant_id, name, price, is_veg, available, stock, flash_sale, active, "
                + "created_at, updated_at) VALUES (" + restaurantId + ", 'Festival Thali', 300.00, TRUE, TRUE, " + stock
                + ", TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    @Test
    void threeHundredBuyersFiftyUnits_losersNeverReachMysql() throws Exception {
        Long thali = insertHotItem(50);
        List<String> customers = customers(300);

        List<HttpResponse<String>> responses = runConcurrently(300, i ->
                placeOrder(customers.get(i), "[{\"menuItemId\":" + thali + ",\"quantity\":1}]", null));

        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(50);
        List<HttpResponse<String>> rejected = responses.stream().filter(r -> r.statusCode() == 409).toList();
        assertThat(rejected).hasSize(250).allSatisfy(r -> assertThat(r.body()).contains("SOLD_OUT"));
        // no loser got INSUFFICIENT_STOCK: none of them reached the MySQL conditional UPDATE
        assertThat(responses).noneMatch(r -> r.body().contains("INSUFFICIENT_STOCK"));
        assertThat(stock(thali)).isZero();
        assertThat(gate.counter(thali)).isZero();
    }

    @Test
    void sameCustomerFiveTimesAtOnce_heldAndCapped() throws Exception {
        Long thali = insertHotItem(100);
        String customer = customers(1).get(0);

        List<Integer> statuses = runConcurrently(5, i ->
                placeOrder(customer, "[{\"menuItemId\":" + thali + ",\"quantity\":1}]", null).statusCode());

        assertThat(statuses).allMatch(s -> s == 201 || s == 409);
        long bought = 100 - stock(thali);
        assertThat(bought).isBetween(1L, 2L); // per-user cap of 2; concurrent attempts blocked by the hold
        assertThat(gate.counter(thali)).isEqualTo(stock(thali));
    }
}
