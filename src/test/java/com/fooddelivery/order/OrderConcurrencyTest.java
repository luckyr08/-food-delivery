package com.fooddelivery.order;

import com.fooddelivery.support.ConcurrencyTestBase;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Order placement and lifecycle under real concurrency (see ConcurrencyTestBase). */
class OrderConcurrencyTest extends ConcurrencyTestBase {

    @Test
    void fiftyCustomersRaceForTenUnits_exactlyTenWin() throws Exception {
        Long biryani = insertMenuItem("Biryani", 10);
        List<String> tokens = customers(50);

        List<Integer> statuses = runConcurrently(tokens.size(), i ->
                placeOrder(tokens.get(i), "[{\"menuItemId\":" + biryani + ",\"quantity\":1}]", null).statusCode());

        Map<Integer, Long> byStatus = countByValue(statuses);
        assertThat(byStatus).containsEntry(201, 10L).containsEntry(409, 40L).hasSize(2);
        assertThat(stock(biryani)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(10);
    }

    @Test
    void oppositeItemOrderDoesNotDeadlock() throws Exception {
        Long a = insertMenuItem("A", 100);
        Long b = insertMenuItem("B", 100);
        List<String> tokens = customers(40);
        String ab = "[{\"menuItemId\":" + a + ",\"quantity\":1},{\"menuItemId\":" + b + ",\"quantity\":1}]";
        String ba = "[{\"menuItemId\":" + b + ",\"quantity\":1},{\"menuItemId\":" + a + ",\"quantity\":1}]";

        List<Integer> statuses = runConcurrently(tokens.size(), i ->
                placeOrder(tokens.get(i), i % 2 == 0 ? ab : ba, null).statusCode());

        assertThat(statuses).containsOnly(201);
        assertThat(stock(a)).isEqualTo(60);
        assertThat(stock(b)).isEqualTo(60);
    }

    @Test
    void sameIdempotencyKeyTenTimesAtOnce_oneOrder() throws Exception {
        Long biryani = insertMenuItem("Biryani", 10);
        String token = customers(1).get(0);
        String items = "[{\"menuItemId\":" + biryani + ",\"quantity\":1}]";

        List<HttpResponse<String>> responses = runConcurrently(10, i -> placeOrder(token, items, "race-key-001"));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isIn(200, 201));
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().map(r -> idOf(r.body())).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(stock(biryani)).isEqualTo(9);
    }

    /**
     * Customer cancels while the owner rejects the same PLACED order, at the same instant, 10 rounds.
     * Both transitions are valid from PLACED, so without protection both could apply their side
     * effects. @Version makes exactly one win; the loser (409) rolls back, so stock is restored once.
     */
    @Test
    void cancelVsRejectRace_exactlyOneWinsAndStockRestoredOnce() throws Exception {
        Long biryani = insertMenuItem("Biryani", 100);
        String customer = customers(1).get(0);

        for (int round = 0; round < 10; round++) {
            long orderId = idOf(placeOrder(customer, "[{\"menuItemId\":" + biryani + ",\"quantity\":2}]", null).body());

            List<HttpResponse<String>> responses = runConcurrently(2, i -> i == 0
                    ? send("POST", "/api/orders/" + orderId + "/cancel", customer, "{}")
                    : send("PATCH", "/api/owner/restaurants/" + restaurantId + "/orders/" + orderId + "/status",
                    ownerToken, "{\"status\":\"REJECTED\",\"reason\":\"Busy\"}"));

            assertThat(responses.stream().map(HttpResponse::statusCode))
                    .as("round %d", round).containsExactlyInAnyOrder(200, 409);
        }

        assertThat(stock(biryani)).isEqualTo(100); // every order restored exactly once
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_status_history WHERE from_status = 'PLACED'", Integer.class))
                .isEqualTo(10); // one winning transition per order
    }
}
