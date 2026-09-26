package com.fooddelivery.payment;

import com.fooddelivery.order.PaymentReconciler;
import com.fooddelivery.support.ConcurrencyTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Webhook and reconciler confirm the same payment at the same instant: exactly one completion, 10 rounds. */
class PaymentWebhookRaceTest extends ConcurrencyTestBase {

    @MockitoBean
    PaymentGateway gateway;

    @Autowired
    PaymentReconciler reconciler;

    @Test
    void webhookAndReconcilerNeverCompleteTwice() throws Exception {
        Long customerId = insertUser("race@example.com", "CUSTOMER");
        when(gateway.status(anyString())).thenAnswer(inv -> new GatewayChargeStatus(true, "pay_" + inv.getArgument(0)));

        for (int round = 0; round < 10; round++) {
            long orderId = insert("INSERT INTO orders (customer_id, restaurant_id, status, subtotal, delivery_fee, "
                    + "total_amount, delivery_address, created_at, updated_at) VALUES (" + customerId + ", "
                    + restaurantId + ", 'PAYMENT_PENDING', 300, 40, 340, 'Flat 4B', "
                    + "UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE, UTC_TIMESTAMP(6))");
            insert("INSERT INTO payments (order_id, amount, method, status, created_at, updated_at) VALUES ("
                    + orderId + ", 340, 'UPI', 'INITIATED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
            byte[] body = ("{\"eventId\":\"evt_race_" + round + "\",\"type\":\"payment.captured\",\"orderReference\":\""
                    + orderId + "\",\"providerRef\":\"pay_" + orderId + "\",\"amount\":340}").getBytes(StandardCharsets.UTF_8);
            long t = Instant.now().getEpochSecond();
            String signature = "t=" + t + ",v1=" + WebhookSignatureVerifier.sign(
                    "local-dev-webhook-secret".getBytes(StandardCharsets.UTF_8), t, body);

            runConcurrently(2, i -> {
                if (i == 0) {
                    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/payments/webhook"))
                            .header("Content-Type", "application/json").header("X-Signature", signature)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode();
                }
                reconciler.reconcile();
                return 0;
            });

            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                    .as("round %d", round).isEqualTo("PLACED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id = ? "
                    + "AND to_status = 'PLACED'", Integer.class, orderId)).as("round %d", round).isEqualTo(1);
        }
    }
}
