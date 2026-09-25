package com.fooddelivery.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** Canonical SHA-256 of a placement request. Line order doesn't matter; content does. */
final class RequestHash {

    private RequestHash() {
    }

    static String of(PlaceOrderRequest request) {
        String lines = request.items().stream()
                .sorted(Comparator.comparing(OrderLineRequest::menuItemId))
                .map(l -> l.menuItemId() + "x" + l.quantity())
                .collect(Collectors.joining(","));
        String canonical = request.restaurantId() + "|" + lines + "|" + request.deliveryAddress()
                + "|" + request.paymentMethod();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
