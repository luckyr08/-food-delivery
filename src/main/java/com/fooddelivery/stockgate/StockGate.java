package com.fooddelivery.stockgate;

import java.util.List;

/**
 * Admission filter for hot items, shaped after Redis + Lua (scripts in resources/redis). It is NEVER the source
 * of truth: MySQL's conditional UPDATE still decides. Drift can only cause a temporary false "sold out" (fixed by
 * resync), never an oversell. All methods may throw StockGateUnavailableException.
 */
public interface StockGate {

    boolean enabled();

    /** Load the counter from MySQL if absent (SET NX). */
    void initIfAbsent(long menuItemId, int mysqlStock);

    /** reserve.lua — all lines or none: per-user hold, per-user cap, stock check, DECRBY, reservation + TTL. */
    GateReservation reserve(long customerId, List<GateLine> lines);

    /** After the MySQL transaction committed: the DB deduction is now the reservation. */
    void confirm(GateReservation reservation);

    /** release.lua — MySQL step failed or was a replay: give the units back. */
    void release(GateReservation reservation);

    /** Counter := MySQL stock - units in unconfirmed reservations (idempotent; after cancellations, periodically). */
    void resync(long menuItemId, int mysqlStock);

    /** sweep.lua — return units of reservations whose TTL passed (crash between reserve and commit). */
    int sweepExpired();
}
