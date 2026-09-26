package com.fooddelivery.payment;

import java.math.BigDecimal;

/**
 * Boundary to an external payment provider (Razorpay/Stripe in production). It is a remote call,
 * so it can never be part of our database transaction — see PaymentService for how we compensate.
 */
public interface PaymentGateway {

    /** Idempotent on request.orderReference(): charging the same order twice returns the first result. */
    ChargeResult charge(ChargeRequest request);

    /** Idempotent on providerRef. */
    void refund(String providerRef, BigDecimal amount);

    /** "Was this order charged?" — used by the reconciler after a timeout or crash. Throws if unreachable. */
    GatewayChargeStatus status(String orderReference);
}
