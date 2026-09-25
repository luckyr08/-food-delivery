package com.fooddelivery.delivery;

import com.fooddelivery.support.ConcurrencyTestBase;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The PDF's "multiple partners contending for the same order", under real concurrency. */
class PartnerClaimConcurrencyTest extends ConcurrencyTestBase {

    /** Places an order and moves it to ACCEPTED so it becomes claimable; returns its id. */
    private long acceptedOrder(String customer, Long itemId) throws Exception {
        long orderId = idOf(placeOrder(customer, "[{\"menuItemId\":" + itemId + ",\"quantity\":1}]", null).body());
        assertThat(send("PATCH", "/api/owner/restaurants/" + restaurantId + "/orders/" + orderId + "/status",
                ownerToken, "{\"status\":\"ACCEPTED\"}").statusCode()).isEqualTo(200);
        return orderId;
    }

    @Test
    void twentyPartnersClaimOneOrder_exactlyOneWins() throws Exception {
        Long item = insertMenuItem("Biryani", 100);
        long orderId = acceptedOrder(customers(1).get(0), item);
        List<String> partners = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            partners.add(insertAvailablePartner("partner" + i + "@example.com"));
        }

        List<HttpResponse<String>> responses = runConcurrently(20, i ->
                send("POST", "/api/partner/orders/" + orderId + "/claim", partners.get(i), ""));

        assertThat(responses.stream().filter(r -> r.statusCode() == 200)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409))
                .hasSize(19)
                .allSatisfy(r -> assertThat(r.body()).contains("ORDER_ALREADY_ASSIGNED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_partners WHERE status = 'BUSY'", Integer.class))
                .isEqualTo(1);
        // the BUSY partner is exactly the one assigned to the order
        assertThat(jdbc.queryForObject("SELECT p.status FROM orders o JOIN delivery_partners p "
                + "ON p.id = o.delivery_partner_id WHERE o.id = ?", String.class, orderId)).isEqualTo("BUSY");
    }

    @Test
    void onePartnerClaimsFiveOrdersAtOnce_getsExactlyOne() throws Exception {
        Long item = insertMenuItem("Biryani", 100);
        String customer = customers(1).get(0);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            orders.add(acceptedOrder(customer, item));
        }
        String partner = insertAvailablePartner("solo@example.com");

        List<Integer> statuses = runConcurrently(5, i ->
                send("POST", "/api/partner/orders/" + orders.get(i) + "/claim", partner, "").statusCode());

        assertThat(countByValue(statuses)).containsEntry(200, 1L).containsEntry(409, 4L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE delivery_partner_id IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }
}
