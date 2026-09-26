package com.fooddelivery.payment;

/** The gateway's view of an order reference: charged (with its provider reference) or not. */
public record GatewayChargeStatus(boolean charged, String providerRef) {

    public static GatewayChargeStatus notCharged() {
        return new GatewayChargeStatus(false, null);
    }
}
