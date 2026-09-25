package com.fooddelivery.payment;

import java.math.BigDecimal;

/** orderReference doubles as the gateway-side idempotency reference. */
public record ChargeRequest(String orderReference, BigDecimal amount, PaymentMethod method) {
}
