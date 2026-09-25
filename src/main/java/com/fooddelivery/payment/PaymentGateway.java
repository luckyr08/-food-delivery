package com.fooddelivery.payment;

import java.math.BigDecimal;

/**
 * Boundary to an external payment provider (Razorpay/Stripe in production). It is a remote call,
 * so it can never be part of our database transaction — see PaymentService for how we compensate.
 */
public interface PaymentGateway {

    ChargeResult charge(ChargeRequest request);

    void refund(String providerRef, BigDecimal amount);
}
