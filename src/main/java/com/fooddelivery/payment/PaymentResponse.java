package com.fooddelivery.payment;

import java.math.BigDecimal;

public record PaymentResponse(PaymentMethod method, PaymentStatus status, BigDecimal amount, String providerRef) {

    public static PaymentResponse from(Payment p) {
        return p == null ? null : new PaymentResponse(p.getMethod(), p.getStatus(), p.getAmount(), p.getProviderRef());
    }
}
