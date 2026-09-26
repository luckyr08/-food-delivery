package com.fooddelivery.payment;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The gateway's charge call "times out", so orders sit in PAYMENT_PENDING until a webhook arrives. */
class PaymentWebhookIntegrationTest extends IntegrationTestBase {

    static final byte[] SECRET = "local-dev-webhook-secret".getBytes(StandardCharsets.UTF_8);

    @MockitoBean
    PaymentGateway gateway;

    @Autowired
    com.fooddelivery.order.PaymentReconciler reconciler;

    Long restaurant;
    Long biryani;
    String customer;

    @BeforeEach
    void setUp() throws Exception {
        String admin = adminToken();
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), createCity(admin, "Pune"), "Spice Hub");
        String owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        biryani = createMenuItem(owner, restaurant, "Biryani", "300.00", 5);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
        when(gateway.charge(any())).thenThrow(new RuntimeException("read timed out"));
    }

    private Long pendingOrder() throws Exception {
        return idFrom(postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":1}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, biryani)).andExpect(status().isAccepted()), "$.id");
    }

    private ResultActions webhook(String json, long timestamp, byte[] secret) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Signature", "t=" + timestamp + ",v1=" + WebhookSignatureVerifier.sign(secret, timestamp, body)));
    }

    private ResultActions webhook(String json) throws Exception {
        return webhook(json, Instant.now().getEpochSecond(), SECRET);
    }

    private static String captured(String eventId, Long orderId, String amount) {
        return """
                {"eventId":"%s","type":"payment.captured","orderReference":"%d","providerRef":"pay_%s","amount":%s}
                """.formatted(eventId, orderId, eventId, amount);
    }

    private String orderStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    private String paymentStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, id);
    }

    @Test
    void capturedWebhookCompletesThePendingOrder() throws Exception {
        Long id = pendingOrder();

        webhook(captured("evt_1", id, "340.00")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));

        assertThat(orderStatus(id)).isEqualTo("PLACED");
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT provider_ref FROM payments WHERE order_id = ?", String.class, id))
                .isEqualTo("pay_evt_1");
    }

    @Test
    void redeliveredEventHasNoEffect() throws Exception {
        Long id = pendingOrder();
        webhook(captured("evt_1", id, "340.00")).andExpect(jsonPath("$.status").value("PROCESSED"));

        webhook(captured("evt_1", id, "340.00")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DUPLICATE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? AND to_status = 'PLACED'",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void forgedStaleOrMissingSignaturesAreRejected() throws Exception {
        Long id = pendingOrder();
        String json = captured("evt_x", id, "340.00");

        webhook(json, Instant.now().getEpochSecond(), "attacker-secret".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));
        webhook(json, Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond(), SECRET)
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
        assertThat(orderStatus(id)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void failedWebhookCancelsAndReleasesStock() throws Exception {
        Long id = pendingOrder();

        webhook("""
                {"eventId":"evt_f","type":"payment.failed","orderReference":"%d","reason":"UPI mandate rejected"}
                """.formatted(id)).andExpect(jsonPath("$.status").value("PROCESSED"));

        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, biryani)).isEqualTo(5);
    }

    @Test
    void captureAfterExpiryIsRefundedAutomatically() throws Exception {
        Long id = pendingOrder();
        when(gateway.status(anyString())).thenReturn(GatewayChargeStatus.notCharged());
        reconciler.reconcile(); // too young: untouched
        assertThat(orderStatus(id)).isEqualTo("PAYMENT_PENDING");
        jdbc.update("UPDATE orders SET created_at = UTC_TIMESTAMP(6) - INTERVAL 20 MINUTE WHERE id = ?", id);
        reconciler.reconcile(); // expired and not charged -> cancelled
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");

        webhook(captured("evt_late", id, "340.00")).andExpect(jsonPath("$.status").value("PROCESSED"));

        assertThat(paymentStatus(id)).isEqualTo("REFUND_PENDING");
        outboxRelay.drain(); // refund runs after commit
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
        assertThat(orderStatus(id)).isEqualTo("CANCELLED");
    }

    @Test
    void amountMismatchIsRejectedAndOrderStaysPending() throws Exception {
        Long id = pendingOrder();

        webhook(captured("evt_m", id, "1.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(orderStatus(id)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void refundProcessedWebhookConfirmsTheRefund() throws Exception {
        Long id = pendingOrder();
        webhook(captured("evt_c", id, "340.00"));
        postAs(customer, "/api/orders/" + id + "/cancel", "{}").andExpect(status().isOk());
        assertThat(paymentStatus(id)).isEqualTo("REFUND_PENDING");

        webhook("""
                {"eventId":"evt_r","type":"refund.processed","orderReference":"%d","providerRef":"pay_evt_c"}
                """.formatted(id)).andExpect(jsonPath("$.status").value("PROCESSED"));
        assertThat(paymentStatus(id)).isEqualTo("REFUNDED");
    }
}
