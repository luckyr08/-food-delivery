package com.fooddelivery.order;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderPlacementIntegrationTest extends IntegrationTestBase {

    String admin;
    String owner;
    String customer;
    Long restaurant;
    Long dal;       // 150.00, unlimited
    Long biryani;   // 300.00, stock 5

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        Long city = createCity(admin, "Pune");
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), city, "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        dal = createMenuItem(owner, restaurant, "Dal", "150.00", null);
        biryani = createMenuItem(owner, restaurant, "Biryani", "300.00", 5);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
    }

    private String body(String items) {
        return """
                {"restaurantId":%d,"items":[%s],"deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, items);
    }

    private String line(Long itemId, int qty) {
        return "{\"menuItemId\":%d,\"quantity\":%d}".formatted(itemId, qty);
    }

    private Integer stockOf(Long itemId) {
        return jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, itemId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void placesOrderWithServerSidePricesAndDeductsStock() throws Exception {
        postAs(customer, "/api/orders", body(line(dal, 1) + "," + line(biryani, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.subtotal").value(750.00))
                .andExpect(jsonPath("$.deliveryFee").value(0))          // >= 500: free delivery
                .andExpect(jsonPath("$.totalAmount").value(750.00))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.providerRef").exists());

        assertThat(stockOf(biryani)).isEqualTo(3);
        assertThat(stockOf(dal)).isNull(); // unlimited stays unlimited
        // saga: PAYMENT_PENDING (stock reserved) -> PLACED (charged after commit)
        assertThat(jdbc.queryForList("SELECT to_status FROM order_status_history ORDER BY id", String.class))
                .containsExactly("PAYMENT_PENDING", "PLACED");
    }

    @Test
    void clientCannotInjectPrices() throws Exception {
        String withPrice = body("{\"menuItemId\":%d,\"quantity\":1,\"unitPrice\":1}".formatted(dal));

        postAs(customer, "/api/orders", withPrice)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(150.00))
                .andExpect(jsonPath("$.deliveryFee").value(40.00));
    }

    @Test
    void priceChangeLaterDoesNotAlterPlacedOrder() throws Exception {
        Long orderId = idFrom(postAs(customer, "/api/orders", body(line(dal, 1))), "$.id");
        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/menu-items/" + dal, """
                {"price":999}
                """).andExpect(status().isOk());

        getAs(customer, "/api/orders/" + orderId)
                .andExpect(jsonPath("$.items[0].unitPrice").value(150.00));
    }

    @Test
    void insufficientStockRollsBackEverything() throws Exception {
        postAs(customer, "/api/orders", body(line(dal, 1) + "," + line(biryani, 6)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(stockOf(biryani)).isEqualTo(5);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test
    void closedRestaurantRejectsOrders() throws Exception {
        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/status", """
                {"open":false}
                """);

        postAs(customer, "/api/orders", body(line(dal, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTAURANT_CLOSED"));
    }

    @Test
    void unavailableItemIsRejected() throws Exception {
        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/menu-items/" + dal, """
                {"available":false}
                """);

        postAs(customer, "/api/orders", body(line(dal, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));
    }

    @Test
    void itemFromAnotherRestaurantIsRejected() throws Exception {
        Long other = createRestaurant(admin, createOwner(admin, "o2@example.com"),
                createCity(admin, "Goa"), "Other");
        Long foreignItem = createMenuItem(login("o2@example.com", "secret123"), other, "Fish", "200", null);

        postAs(customer, "/api/orders", body(line(foreignItem, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_RESTAURANT"));
    }

    @Test
    void duplicateLinesAreRejected() throws Exception {
        postAs(customer, "/api/orders", body(line(dal, 1) + "," + line(dal, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_ITEM"));
    }

    @Test
    void invalidQuantityAndEmptyItemsAreValidationErrors() throws Exception {
        postAs(customer, "/api/orders", body(line(dal, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postAs(customer, "/api/orders", body(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashOnDeliveryPaymentIsPending() throws Exception {
        postAs(customer, "/api/orders", body(line(dal, 1)).replace("UPI", "CASH_ON_DELIVERY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("PENDING"));
    }

    @Test
    void onlyCustomersCanOrder() throws Exception {
        postAs(owner, "/api/orders", body(line(dal, 1))).andExpect(status().isForbidden());
    }

    @Test
    void idempotentReplayReturnsSameOrderWithoutSideEffects() throws Exception {
        var first = mvc.perform(post("/api/orders").header("Authorization", bearer(customer))
                        .header("Idempotency-Key", "key-12345678")
                        .contentType(MediaType.APPLICATION_JSON).content(body(line(biryani, 1))))
                .andExpect(status().isCreated());
        Long orderId = idFrom(first, "$.id");

        mvc.perform(post("/api/orders").header("Authorization", bearer(customer))
                        .header("Idempotency-Key", "key-12345678")
                        .contentType(MediaType.APPLICATION_JSON).content(body(line(biryani, 1))))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(orderId));

        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf(biryani)).isEqualTo(4);
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() throws Exception {
        mvc.perform(post("/api/orders").header("Authorization", bearer(customer))
                        .header("Idempotency-Key", "key-12345678")
                        .contentType(MediaType.APPLICATION_JSON).content(body(line(biryani, 1))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/orders").header("Authorization", bearer(customer))
                        .header("Idempotency-Key", "key-12345678")
                        .contentType(MediaType.APPLICATION_JSON).content(body(line(biryani, 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void customersSeeOnlyTheirOwnOrders() throws Exception {
        Long orderId = idFrom(postAs(customer, "/api/orders", body(line(dal, 1))), "$.id");
        registerCustomer("other@example.com", "secret123");
        String other = login("other@example.com", "secret123");

        getAs(other, "/api/orders/" + orderId).andExpect(status().isNotFound());
        getAs(other, "/api/orders").andExpect(jsonPath("$.totalElements").value(0));
        getAs(customer, "/api/orders")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].restaurantName").value("Spice Hub"));
    }
}
