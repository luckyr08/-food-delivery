package com.fooddelivery.stockgate;

import com.fooddelivery.payment.ChargeResult;
import com.fooddelivery.payment.PaymentGateway;
import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {"app.stock-gate.mode=redis", "app.stock-gate.max-units-per-user=2"})
class StockGateIntegrationTest extends IntegrationTestBase {

    @Autowired
    SimulatedRedisStockGate gate;

    @MockitoBean
    PaymentGateway paymentGateway;

    Long restaurant;
    Long thali;
    String customer;

    @BeforeEach
    void setUp() throws Exception {
        gate.reset();
        when(paymentGateway.charge(any())).thenReturn(ChargeResult.approved("pay_ok"));
        String admin = adminToken();
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), createCity(admin, "Pune"), "Spice Hub");
        String owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        thali = idFrom(postAs(owner, "/api/owner/restaurants/" + restaurant + "/menu-items", """
                {"name":"Festival Thali","price":499,"stock":10,"flashSale":true}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.flashSale").value(true)), "$.id");
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
    }

    private org.springframework.test.web.servlet.ResultActions order(int qty) throws Exception {
        return postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":%d}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, thali, qty));
    }

    private int mysqlStock() {
        return jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, thali);
    }

    @Test
    void perCustomerCapIsEnforcedByTheGate() throws Exception {
        order(2).andExpect(status().isCreated());
        order(1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_LIMIT"));
        assertThat(mysqlStock()).isEqualTo(8);
        assertThat(gate.counter(thali)).isEqualTo(8);
    }

    @Test
    void declinedPaymentReturnsUnitsToMysqlAndThenToTheGate() throws Exception {
        when(paymentGateway.charge(any())).thenReturn(ChargeResult.declined("insufficient funds"));

        order(1).andExpect(status().isPaymentRequired());

        assertThat(mysqlStock()).isEqualTo(10);          // MySQL restocked by the saga's compensation
        assertThat(gate.counter(thali)).isEqualTo(9);    // gate catches up after commit...
        outboxRelay.drain();
        assertThat(gate.counter(thali)).isEqualTo(10);   // ...via the outbox re-sync from MySQL
    }

    @Test
    void abandonedReservationIsSweptBack() {
        gate.initIfAbsent(thali, 10);
        gate.reserve(999L, List.of(new GateLine(thali, 1))); // "crash": never confirmed nor released
        assertThat(gate.counter(thali)).isEqualTo(9);

        assertThat(gate.sweepExpiredAt(Instant.now().plus(Duration.ofMinutes(1)))).isEqualTo(1);
        assertThat(gate.counter(thali)).isEqualTo(10);
    }

    @Test
    void gateDownFailsOpenToMysql() throws Exception {
        gate.setAvailable(false);

        order(1).andExpect(status().isCreated());       // MySQL alone is still correct
        assertThat(mysqlStock()).isEqualTo(9);
    }

    @Test
    void mysqlStillDecidesWhenTheGateIsWrong() throws Exception {
        gate.initIfAbsent(thali, 10);
        jdbc.update("UPDATE menu_items SET stock = 0 WHERE id = ?", thali); // drift: gate thinks 10 are left

        order(1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(gate.counter(thali)).isEqualTo(10);   // reservation released after MySQL said no
        assertThat(mysqlStock()).isZero();               // never oversold
    }
}
