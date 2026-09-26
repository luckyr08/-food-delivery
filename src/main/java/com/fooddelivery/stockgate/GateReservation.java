package com.fooddelivery.stockgate;

import java.util.List;

/** Units held in the gate for one checkout; NONE when nothing was gated (mysql-only, no hot items, gate down). */
public record GateReservation(String id, long customerId, List<GateLine> lines) {

    public static final GateReservation NONE = new GateReservation(null, 0, List.of());

    public boolean isNone() {
        return id == null;
    }
}
