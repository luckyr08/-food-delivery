package com.fooddelivery.order;

import com.fooddelivery.payment.ChargeResult;
import com.fooddelivery.payment.GatewayChargeStatus;
import com.fooddelivery.payment.PaymentGateway;
import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payment saga: the gateway is never called inside a DB transaction, and every outcome is resolved. */
class PaymentSagaIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    PaymentGateway gateway;

    @Autowired
    PaymentReconciler reconciler;

    Long restaurant;
    Long biryani;
    String customer;
    String owner;

    @BeforeEach
    void setUp() throws Exception {
        String admin = adminToken();
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), createCity(admin, "Pune"), "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        biryani = createMenuItem(owner, restaurant, "Biryani", "300.00", 5);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
    }

    private org.springframework.test.web.servlet.ResultActions order(int qty) throws Exception {
        return postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":%d}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"CARD"}
                """.formatted(restaurant, biryani, qty));
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, biryani);
    }

    private String orderStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    private String paymentStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, id);
    }

    @Test
    void gatewayIsChargedOutsideAnyTransaction() throws Exception {
        when(gateway.charge(any())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("no DB transaction (locks, connection) held during the gateway call").isFalse();
            return ChargeResult.approved("ref_ok");
        });

        order(2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.providerRef").value("ref_ok"));
        assertThat(stock()).isEqualTo(3);
    }

    @Test
    void declineIsCompensated() throws Exception {
        when(gateway.charge(any())).thenReturn(ChargeResult.declined("insufficient funds"));

        order(2).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));
        // the 402 body is a problem document; the saga still recorded the order (then cancelled it)
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM orders", Long.class);

        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(paymentStatus(id)).isEqualTo("FAILED");
        assertThat(stock()).isEqualTo(5); // reservation released
        awaitAsyncWork();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class)).isZero();
        getAs(owner, "/api/owner/restaurants/" + restaurant + "/orders?status=PLACED")
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void timeoutReturns202AndTheReconcilerCompletesIt() throws Exception {
        when(gateway.charge(any())).thenThrow(new RuntimeException("read timed out"));
        Long id = idFrom(order(1).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING")), "$.id");
        // not visible to the restaurant while payment is unresolved
        getAs(owner, "/api/owner/restaurants/" + restaurant + "/orders").andExpect(jsonPath("$.totalElements").value(0));

        // the gateway did charge it after all
        when(gateway.status(String.valueOf(id))).thenReturn(new GatewayChargeStatus(true, "ref_late"));
        var outcome = reconciler.reconcileAt(Instant.now().plus(Duration.ofMinutes(2)));

        assertThat(outcome.completed()).isEqualTo(1);
        assertThat(orderStatus(id)).isEqualTo("PLACED");
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(stock()).isEqualTo(4);
    }

    @Test
    void neverChargedAndExpiredIsCancelledButYoungOrdersAreLeftAlone() throws Exception {
        when(gateway.charge(any())).thenThrow(new RuntimeException("connection reset"));
        Long id = idFrom(order(1).andExpect(status().isAccepted()), "$.id");
        when(gateway.status(anyString())).thenReturn(GatewayChargeStatus.notCharged());

        assertThat(reconciler.reconcileAt(Instant.now().plus(Duration.ofMinutes(5))).left()).isEqualTo(1);
        assertThat(orderStatus(id)).isEqualTo("PAYMENT_PENDING");

        assertThat(reconciler.reconcileAt(Instant.now().plus(Duration.ofMinutes(16))).cancelled()).isEqualTo(1);
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void unreachableGatewayNeverCancelsBlind() throws Exception {
        when(gateway.charge(any())).thenThrow(new RuntimeException("timeout"));
        Long id = idFrom(order(1), "$.id");
        when(gateway.status(anyString())).thenThrow(new RuntimeException("gateway down"));

        assertThat(reconciler.reconcileAt(Instant.now().plus(Duration.ofHours(1))).left()).isEqualTo(1);
        assertThat(orderStatus(id)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void refundsRunAfterCommitAndRetryUntilTheGatewayIsBack() throws Exception {
        when(gateway.charge(any())).thenReturn(ChargeResult.approved("ref_paid"));
        Long id = idFrom(order(1).andExpect(status().isCreated()), "$.id");
        doThrow(new RuntimeException("gateway down")).when(gateway).refund(anyString(), any());

        postAs(customer, "/api/orders/" + id + "/cancel", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("REFUND_PENDING"));

        outboxRelay.drain();
        assertThat(paymentStatus(id)).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_type = 'PAYMENT'",
                Integer.class)).isEqualTo(1);

        doNothing().when(gateway).refund(anyString(), any());
        jdbc.update("UPDATE outbox_event SET next_attempt_at = UTC_TIMESTAMP(6) WHERE processed_at IS NULL");
        outboxRelay.drain();
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
    }
}
