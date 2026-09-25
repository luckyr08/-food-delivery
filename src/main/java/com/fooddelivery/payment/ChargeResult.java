package com.fooddelivery.payment;

public record ChargeResult(boolean approved, String providerRef, String failureReason) {

    public static ChargeResult approved(String providerRef) {
        return new ChargeResult(true, providerRef, null);
    }

    public static ChargeResult declined(String reason) {
        return new ChargeResult(false, null, reason);
    }
}
