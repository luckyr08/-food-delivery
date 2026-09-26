package com.fooddelivery.order;

import com.fooddelivery.support.ConcurrencyTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Festival spike: 200 customers order the same item at the same instant, with the bulkhead squeezed to
 * 4 concurrent placements. The DB must only ever see 4 placements at a time, every request must get a
 * fast, meaningful answer (201 / 409 sold out / 503 retry later), never a 500, and nothing is oversold.
 */
@TestPropertySource(properties = {"app.order.admission.max-concurrent=4", "app.order.admission.acquire-timeout-ms=100"})
class OrderSpikeTest extends ConcurrencyTestBase {

    @Autowired
    OrderAdmissionControl admission;

    @Test
    void spikeIsShedGracefully() throws Exception {
        // Warm-up: a real spike hits a warm server (JIT, DB connections); the first requests after boot are slow.
        Long warmUpItem = insertMenuItem("Warm-up Dal", 1000);
        String warmUpCustomer = token(insertUser("warmup@example.com", "CUSTOMER"), com.fooddelivery.user.Role.CUSTOMER);
        for (int i = 0; i < 30; i++) {
            placeOrder(warmUpCustomer, "[{\"menuItemId\":" + warmUpItem + ",\"quantity\":1}]", null);
        }
        admission.resetStats();

        Long item = insertMenuItem("Festival Biryani", 100);
        List<String> customers = customers(200);

        List<long[]> results = runConcurrently(200, i -> {
            long start = System.nanoTime();
            HttpResponse<String> r = placeOrder(customers.get(i), "[{\"menuItemId\":" + item + ",\"quantity\":1}]", null);
            boolean hasRetryAfter = r.headers().firstValue("Retry-After").isPresent();
            return new long[]{r.statusCode(), (System.nanoTime() - start) / 1_000_000, hasRetryAfter ? 1 : 0};
        });

        Map<Long, Long> byStatus = countByValue(results.stream().map(r -> r[0]).toList());
        long created = byStatus.getOrDefault(201L, 0L);
        assertThat(byStatus.keySet()).isSubsetOf(201L, 409L, 503L);           // never a 500
        assertThat(byStatus.get(503L)).as("bulkhead engaged").isPositive();
        assertThat(results).filteredOn(r -> r[0] == 503).allMatch(r -> r[2] == 1); // Retry-After present
        assertThat(admission.peakInFlight()).isLessThanOrEqualTo(4);            // DB protected
        assertThat(stock(item)).isEqualTo(100 - created).isNotNegative();        // no oversell
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE menu_item_id = ?", Long.class, item))
                .isEqualTo(created);
        long slowest = results.stream().mapToLong(r -> r[1]).max().orElseThrow();
        assertThat(slowest).as("every caller answered quickly, ms").isLessThan(5000);
        System.out.printf("SPIKE: %s, peak in-flight %d, slowest %d ms%n", byStatus, admission.peakInFlight(), slowest);
    }
}
