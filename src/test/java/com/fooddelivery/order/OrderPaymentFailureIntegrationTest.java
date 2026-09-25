package com.fooddelivery.order;

import com.fooddelivery.payment.ChargeResult;
import com.fooddelivery.payment.PaymentGateway;
import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The real gateway is replaced by a Mockito mock so we can force declines. */
class OrderPaymentFailureIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    PaymentGateway gateway;

    Long restaurant;
    Long biryani;
    String customer;

    @BeforeEach
    void setUp() throws Exception {
        String admin = adminToken();
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"),
                createCity(admin, "Pune"), "Spice Hub");
        String owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        biryani = createMenuItem(owner, restaurant, "Biryani", "300.00", 5);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
    }

    @Test
    void declinedPaymentLeavesNoTrace() throws Exception {
        when(gateway.charge(any())).thenReturn(ChargeResult.declined("insufficient funds"));

        postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":2}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"CARD"}
                """.formatted(restaurant, biryani))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

        // stock deducted inside the transaction was rolled back together with the order
        assertThat(jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, biryani))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isZero();
    }
}
