package com.fooddelivery.order;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderLifecycleIntegrationTest extends IntegrationTestBase {

    String admin;
    String owner;
    String customer;
    Long restaurant;
    Long biryani; // stock 10

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"),
                createCity(admin, "Pune"), "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        biryani = createMenuItem(owner, restaurant, "Biryani", "300.00", 10);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
    }

    private Long placeOrder(int qty, String paymentMethod) throws Exception {
        return idFrom(postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":%d}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"%s"}
                """.formatted(restaurant, biryani, qty, paymentMethod)).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions ownerSets(Long orderId, String status, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return patchAs(owner, "/api/owner/restaurants/" + restaurant + "/orders/" + orderId + "/status",
                "{\"status\":\"" + status + "\"" + reasonJson + "}");
    }

    private Integer stock() {
        return jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, biryani);
    }

    private String paymentStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId);
    }

    @Test
    void restaurantMovesOrderThroughKitchenStates() throws Exception {
        Long order = placeOrder(1, "UPI");

        ownerSets(order, "ACCEPTED", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        ownerSets(order, "PREPARING", null).andExpect(jsonPath("$.status").value("PREPARING"));
        ownerSets(order, "READY_FOR_PICKUP", null).andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));

        getAs(customer, "/api/orders/" + order + "/timeline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status",
                        contains("PAYMENT_PENDING", "PLACED", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP")));
    }

    @Test
    void skippingStatesIsRejected() throws Exception {
        Long order = placeOrder(1, "UPI");

        ownerSets(order, "READY_FOR_PICKUP", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void ownerCannotPerformPartnerTransition() throws Exception {
        Long order = placeOrder(1, "UPI");
        ownerSets(order, "ACCEPTED", null);
        ownerSets(order, "PREPARING", null);
        ownerSets(order, "READY_FOR_PICKUP", null);

        ownerSets(order, "OUT_FOR_DELIVERY", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED_FOR_ROLE"));
    }

    @Test
    void rejectNeedsReasonThenRestocksAndRefunds() throws Exception {
        Long order = placeOrder(3, "UPI");
        assertThat(stock()).isEqualTo(7);

        ownerSets(order, "REJECTED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

        ownerSets(order, "REJECTED", "Kitchen closing early")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.payment.status").value("REFUND_PENDING")); // refund runs after commit

        assertThat(stock()).isEqualTo(10);
        outboxRelay.drain();
        assertThat(paymentStatus(order)).isEqualTo("REFUNDED");
        getAs(customer, "/api/orders/" + order + "/timeline")
                .andExpect(jsonPath("$[2].note").value("Kitchen closing early"));
    }

    @Test
    void customerCancelsBeforePreparing() throws Exception {
        Long order = placeOrder(2, "UPI");
        ownerSets(order, "ACCEPTED", null);

        postAs(customer, "/api/orders/" + order + "/cancel", "{\"reason\":\"Changed my mind\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stock()).isEqualTo(10);
        assertThat(paymentStatus(order)).isEqualTo("REFUND_PENDING");
        outboxRelay.drain();
        assertThat(paymentStatus(order)).isEqualTo("REFUNDED");
    }

    @Test
    void customerCannotCancelOncePreparing() throws Exception {
        Long order = placeOrder(1, "UPI");
        ownerSets(order, "ACCEPTED", null);
        ownerSets(order, "PREPARING", null);

        postAs(customer, "/api/orders/" + order + "/cancel", "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TRANSITION_NOT_ALLOWED_FOR_ROLE"));
    }

    @Test
    void cancellingCashOnDeliveryVoidsThePayment() throws Exception {
        Long order = placeOrder(1, "CASH_ON_DELIVERY");

        postAs(customer, "/api/orders/" + order + "/cancel", "{}").andExpect(status().isOk());

        assertThat(paymentStatus(order)).isEqualTo("VOIDED");
    }

    @Test
    void finalStateCannotChange() throws Exception {
        Long order = placeOrder(1, "UPI");
        postAs(customer, "/api/orders/" + order + "/cancel", "{}").andExpect(status().isOk());

        ownerSets(order, "ACCEPTED", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        assertThat(stock()).isEqualTo(10); // not restocked twice
    }

    @Test
    void adminCancelsCookedOrder_refundButNoRestock() throws Exception {
        Long order = placeOrder(2, "UPI");
        ownerSets(order, "ACCEPTED", null);
        ownerSets(order, "PREPARING", null);

        postAs(admin, "/api/admin/orders/" + order + "/cancel", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        postAs(admin, "/api/admin/orders/" + order + "/cancel", "{\"reason\":\"Customer unreachable\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("REFUND_PENDING"));

        assertThat(stock()).isEqualTo(8); // food was already cooked
    }

    @Test
    void ownerQueueFiltersByStatus() throws Exception {
        Long first = placeOrder(1, "UPI");
        placeOrder(1, "UPI");
        ownerSets(first, "ACCEPTED", null);

        getAs(owner, "/api/owner/restaurants/" + restaurant + "/orders?status=PLACED")
                .andExpect(jsonPath("$.totalElements").value(1));
        getAs(owner, "/api/owner/restaurants/" + restaurant + "/orders")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first)); // oldest first
    }

    @Test
    void otherOwnerAndForeignOrderIdsAre404() throws Exception {
        Long order = placeOrder(1, "UPI");
        Long otherRestaurant = createRestaurant(admin, createOwner(admin, "o2@example.com"),
                createCity(admin, "Goa"), "Other");
        String otherOwner = login("o2@example.com", "secret123");

        // someone else's restaurant
        patchAs(otherOwner, "/api/owner/restaurants/" + restaurant + "/orders/" + order + "/status",
                "{\"status\":\"ACCEPTED\"}").andExpect(status().isNotFound());
        // own restaurant id + someone else's order id (IDOR)
        patchAs(otherOwner, "/api/owner/restaurants/" + otherRestaurant + "/orders/" + order + "/status",
                "{\"status\":\"ACCEPTED\"}").andExpect(status().isNotFound());
    }

    @Test
    void customerCannotSeeOthersTimelineOrCancelTheirOrder() throws Exception {
        Long order = placeOrder(1, "UPI");
        registerCustomer("other@example.com", "secret123");
        String other = login("other@example.com", "secret123");

        getAs(other, "/api/orders/" + order + "/timeline").andExpect(status().isNotFound());
        postAs(other, "/api/orders/" + order + "/cancel", "{}").andExpect(status().isNotFound());
    }
}
