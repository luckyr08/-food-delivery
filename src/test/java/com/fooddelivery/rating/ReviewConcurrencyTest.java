package com.fooddelivery.rating;

import com.fooddelivery.support.ConcurrencyTestBase;
import com.fooddelivery.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Delivered orders are inserted directly; only the review submissions run concurrently. */
class ReviewConcurrencyTest extends ConcurrencyTestBase {

    Long partnerId;

    @BeforeEach
    void setUpPartner() {
        insertAvailablePartner("partner@example.com");
        partnerId = jdbc.queryForObject("SELECT id FROM delivery_partners", Long.class);
    }

    private long insertDeliveredOrder(Long customerId) {
        long orderId = insert("INSERT INTO orders (customer_id, restaurant_id, delivery_partner_id, status, subtotal, "
                + "delivery_fee, total_amount, delivery_address, created_at, updated_at) VALUES (" + customerId
                + ", " + restaurantId + ", " + partnerId + ", 'DELIVERED', 300, 40, 340, 'Flat 4B', "
                + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        insert("INSERT INTO order_status_history (order_id, from_status, to_status, changed_at) VALUES ("
                + orderId + ", 'OUT_FOR_DELIVERY', 'DELIVERED', UTC_TIMESTAMP(6))");
        return orderId;
    }

    @Test
    void thirtyConcurrentReviewsOfOneRestaurant_noneLostNoDeadlock() throws Exception {
        List<String> tokens = new ArrayList<>();
        List<Long> orders = new ArrayList<>();
        int expectedSum = 0;
        for (int i = 0; i < 30; i++) {
            Long customerId = insertUser("reviewer" + i + "@example.com", "CUSTOMER");
            tokens.add(token(customerId, Role.CUSTOMER));
            orders.add(insertDeliveredOrder(customerId));
            expectedSum += i % 5 + 1;
        }

        List<Integer> statuses = runConcurrently(30, i -> send("POST", "/api/orders/" + orders.get(i) + "/review",
                tokens.get(i), "{\"restaurantRating\":" + (i % 5 + 1) + ",\"partnerRating\":5}").statusCode());

        assertThat(statuses).containsOnly(201);
        var restaurant = jdbc.queryForMap("SELECT rating_sum, rating_count FROM restaurants WHERE id = ?", restaurantId);
        assertThat(((Number) restaurant.get("rating_count")).intValue()).isEqualTo(30);
        assertThat(((Number) restaurant.get("rating_sum")).intValue()).isEqualTo(expectedSum);
        assertThat(jdbc.queryForObject("SELECT rating_count FROM delivery_partners WHERE id = ?", Integer.class,
                partnerId)).isEqualTo(30);
    }

    @Test
    void sameReviewFiveTimesAtOnce_countedOnce() throws Exception {
        Long customerId = insertUser("double@example.com", "CUSTOMER");
        String token = token(customerId, Role.CUSTOMER);
        long orderId = insertDeliveredOrder(customerId);

        List<Integer> statuses = runConcurrently(5, i ->
                send("POST", "/api/orders/" + orderId + "/review", token, "{\"restaurantRating\":5}").statusCode());

        assertThat(countByValue(statuses)).containsEntry(201, 1L).containsEntry(409, 4L);
        assertThat(jdbc.queryForObject("SELECT rating_count FROM restaurants WHERE id = ?", Integer.class,
                restaurantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Integer.class)).isEqualTo(1);
    }
}
