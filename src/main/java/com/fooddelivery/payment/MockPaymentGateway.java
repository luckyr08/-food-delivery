package com.fooddelivery.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** In-process stand-in: approves every charge instantly. Tests replace it with a Mockito mock. */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public ChargeResult charge(ChargeRequest request) {
        String ref = "mock_" + UUID.randomUUID();
        log.info("Mock charge {} {} for order {} -> {}", request.method(), request.amount(),
                request.orderReference(), ref);
        return ChargeResult.approved(ref);
    }

    @Override
    public void refund(String providerRef, BigDecimal amount) {
        log.info("Mock refund {} of {}", providerRef, amount);
    }
}
