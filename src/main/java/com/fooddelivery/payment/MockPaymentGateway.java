package com.fooddelivery.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process stand-in: approves every charge instantly. Like a real gateway it is idempotent on the order
 * reference (a retried charge returns the original result) and can be queried for a charge's status.
 * Tests replace it with a Mockito mock to force declines, timeouts and outages.
 */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    private final Map<String, String> chargedByOrder = new ConcurrentHashMap<>();
    private final Set<String> refunded = ConcurrentHashMap.newKeySet();

    @Override
    public ChargeResult charge(ChargeRequest request) {
        String ref = chargedByOrder.computeIfAbsent(request.orderReference(), k -> "mock_" + UUID.randomUUID());
        log.info("Mock charge {} {} for order {} -> {}", request.method(), request.amount(),
                request.orderReference(), ref);
        return ChargeResult.approved(ref);
    }

    @Override
    public void refund(String providerRef, BigDecimal amount) {
        if (refunded.add(providerRef)) {
            log.info("Mock refund {} of {}", providerRef, amount);
        }
    }

    @Override
    public GatewayChargeStatus status(String orderReference) {
        String ref = chargedByOrder.get(orderReference);
        return ref == null ? GatewayChargeStatus.notCharged() : new GatewayChargeStatus(true, ref);
    }
}
