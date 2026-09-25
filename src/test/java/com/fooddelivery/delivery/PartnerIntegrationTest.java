package com.fooddelivery.delivery;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartnerIntegrationTest extends IntegrationTestBase {

    String admin;
    String owner;
    String customer;
    String partner;
    Long city;
    Long restaurant;
    Long dal;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        city = createCity(admin, "Pune");
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), city, "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        dal = createMenuItem(owner, restaurant, "Dal", "150.00", null);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
        partner = createPartner("kiran@example.com", city);
        goOnline(partner);
    }

    private String createPartner(String email, Long cityId) throws Exception {
        postAs(admin, "/api/admin/delivery-partners", """
                {"name":"Kiran","email":"%s","password":"secret123","phone":"9876543210",
                 "cityId":%d,"vehicleType":"SCOOTER"}
                """.formatted(email, cityId)).andExpect(status().isCreated());
        return login(email, "secret123");
    }

    private void goOnline(String partnerToken) throws Exception {
        patchAs(partnerToken, "/api/partner/me/status", "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk());
    }

    private Long placeOrder(String paymentMethod) throws Exception {
        return idFrom(postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":1}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"%s"}
                """.formatted(restaurant, dal, paymentMethod)).andExpect(status().isCreated()), "$.id");
    }

    private void ownerSets(Long orderId, String status) throws Exception {
        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/orders/" + orderId + "/status",
                "{\"status\":\"" + status + "\"}").andExpect(status().isOk());
    }

    private ResultActions partnerSets(String token, Long orderId, String status) throws Exception {
        return patchAs(token, "/api/partner/orders/" + orderId + "/status", "{\"status\":\"" + status + "\"}");
    }

    private ResultActions claim(String token, Long orderId) throws Exception {
        return mvc.perform(post("/api/partner/orders/" + orderId + "/claim").header("Authorization", bearer(token)));
    }

    private String partnerStatus() {
        return jdbc.queryForObject("SELECT p.status FROM delivery_partners p JOIN users u ON u.id = p.user_id "
                + "WHERE u.email = 'kiran@example.com'", String.class);
    }

    @Test
    void fullDeliveryFlowWithCashOnDelivery() throws Exception {
        Long order = placeOrder("CASH_ON_DELIVERY");
        ownerSets(order, "ACCEPTED");

        getAs(partner, "/api/partner/orders/available")
                .andExpect(jsonPath("$.content[*].orderId", contains(order.intValue())))
                .andExpect(jsonPath("$.content[0].pickupAddress").value("1 Test Road"));

        claim(partner, order)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryPartner.name").value("Kiran"));
        assertThat(partnerStatus()).isEqualTo("BUSY");
        getAs(partner, "/api/partner/orders/available").andExpect(jsonPath("$.totalElements").value(0));

        // customer can see who is coming
        getAs(customer, "/api/orders/" + order)
                .andExpect(jsonPath("$.deliveryPartner.vehicleType").value("SCOOTER"));

        ownerSets(order, "PREPARING");
        ownerSets(order, "READY_FOR_PICKUP");
        partnerSets(partner, order, "OUT_FOR_DELIVERY").andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"));
        getAs(partner, "/api/partner/orders/current").andExpect(jsonPath("$.id").value(order));
        partnerSets(partner, order, "DELIVERED")
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS")); // cash collected

        assertThat(partnerStatus()).isEqualTo("AVAILABLE");
        getAs(partner, "/api/partner/orders/current").andExpect(status().isNoContent());
        getAs(customer, "/api/orders/" + order + "/timeline")
                .andExpect(jsonPath("$[*].status", contains("PLACED", "ACCEPTED", "PREPARING",
                        "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED")));
    }

    @Test
    void cannotPickUpBeforeFoodIsReady() throws Exception {
        Long order = placeOrder("UPI");
        ownerSets(order, "ACCEPTED");
        claim(partner, order).andExpect(status().isOk());

        partnerSets(partner, order, "OUT_FOR_DELIVERY")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void placedOrderIsNotClaimable() throws Exception {
        Long order = placeOrder("UPI");

        claim(partner, order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CLAIMABLE"));
    }

    @Test
    void offlinePartnerCannotClaim() throws Exception {
        Long order = placeOrder("UPI");
        ownerSets(order, "ACCEPTED");
        patchAs(partner, "/api/partner/me/status", "{\"status\":\"OFFLINE\"}").andExpect(status().isOk());

        claim(partner, order)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTNER_NOT_AVAILABLE"));
    }

    @Test
    void busyPartnerCannotGoOfflineOrSetBusyManually() throws Exception {
        Long order = placeOrder("UPI");
        ownerSets(order, "ACCEPTED");
        claim(partner, order);

        patchAs(partner, "/api/partner/me/status", "{\"status\":\"OFFLINE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTNER_BUSY"));
        patchAs(partner, "/api/partner/me/status", "{\"status\":\"BUSY\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTNER_STATUS"));
    }

    @Test
    void otherCityOrdersAreInvisible() throws Exception {
        Long order = placeOrder("UPI");
        ownerSets(order, "ACCEPTED");
        String goaPartner = createPartner("goa@example.com", createCity(admin, "Goa"));
        goOnline(goaPartner);

        getAs(goaPartner, "/api/partner/orders/available").andExpect(jsonPath("$.totalElements").value(0));
        claim(goaPartner, order).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheAssignedPartnerCanUpdate() throws Exception {
        Long order = placeOrder("UPI");
        ownerSets(order, "ACCEPTED");
        claim(partner, order);
        ownerSets(order, "PREPARING");
        ownerSets(order, "READY_FOR_PICKUP");
        String other = createPartner("other@example.com", city);

        partnerSets(other, order, "OUT_FOR_DELIVERY").andExpect(status().isNotFound());
    }

    @Test
    void cancellingAnAssignedOrderFreesThePartner() throws Exception {
        Long order = placeOrder("UPI");
        ownerSets(order, "ACCEPTED");
        claim(partner, order);
        assertThat(partnerStatus()).isEqualTo("BUSY");

        postAs(customer, "/api/orders/" + order + "/cancel", "{}").andExpect(status().isOk());

        assertThat(partnerStatus()).isEqualTo("AVAILABLE");
        getAs(partner, "/api/partner/orders/current").andExpect(status().isNoContent());
    }

    @Test
    void customerCannotUsePartnerApi() throws Exception {
        getAs(customer, "/api/partner/orders/available").andExpect(status().isForbidden());
    }
}
