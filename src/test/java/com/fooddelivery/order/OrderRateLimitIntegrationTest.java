package com.fooddelivery.order;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"app.order.rate-limit.capacity=2", "app.order.rate-limit.refill-per-minute=2"})
class OrderRateLimitIntegrationTest extends IntegrationTestBase {

    @Test
    void thirdOrderInABurstIs429WithRetryAfter() throws Exception {
        String admin = adminToken();
        Long restaurant = createRestaurant(admin, createOwner(admin, "o@example.com"), createCity(admin, "Pune"), "R");
        String owner = login("o@example.com", "secret123");
        openRestaurant(owner, restaurant);
        Long dal = createMenuItem(owner, restaurant, "Dal", "150", null);
        registerCustomer("c@example.com", "secret123");
        String customer = login("c@example.com", "secret123");
        String body = """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":1}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, dal);

        postAs(customer, "/api/orders", body).andExpect(status().isCreated());
        postAs(customer, "/api/orders", body).andExpect(status().isCreated());
        postAs(customer, "/api/orders", body)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"));
    }
}
