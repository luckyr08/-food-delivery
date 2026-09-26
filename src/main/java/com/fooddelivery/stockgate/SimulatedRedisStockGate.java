package com.fooddelivery.stockgate;

import com.fooddelivery.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SIMULATED Redis for app.stock-gate.mode=redis. Mirrors the keys and Lua scripts in resources/redis:
 *   stock:{item}            counter            hold:{item}:{user}  per-user lock (NX + TTL)
 *   bought:{item}:{user}    per-user cap       resv:{id}           reservation (+ resv:expiry sorted set)
 * Every public method is synchronized = one Lua script = atomic, exactly like Redis's single-threaded execution.
 * Limitation: in one JVM, so not shared between app instances (a real Redis is).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.stock-gate.mode", havingValue = "redis")
public class SimulatedRedisStockGate implements StockGate {

    private record Resv(long customerId, List<GateLine> lines, Instant expiresAt) {
    }

    private final Map<Long, Integer> stock = new HashMap<>();
    private final Map<String, Instant> holds = new HashMap<>();
    private final Map<String, Integer> bought = new HashMap<>();
    private final Map<String, Resv> reservations = new HashMap<>();
    private final Clock clock;
    private final Duration ttl;
    private final int maxUnitsPerUser;
    private volatile boolean available = true;

    public SimulatedRedisStockGate(Clock clock,
                                   @Value("${app.stock-gate.reservation-ttl-seconds:30}") long ttlSeconds,
                                   @Value("${app.stock-gate.max-units-per-user:2}") int maxUnitsPerUser) {
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.maxUnitsPerUser = maxUnitsPerUser;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public synchronized void initIfAbsent(long menuItemId, int mysqlStock) {
        ensureAvailable();
        stock.putIfAbsent(menuItemId, mysqlStock);
    }

    @Override
    public synchronized GateReservation reserve(long customerId, List<GateLine> lines) {
        ensureAvailable();
        Instant now = clock.instant();
        for (GateLine line : lines) { // check everything first: all lines or none
            Instant hold = holds.get(holdKey(line.menuItemId(), customerId));
            if (hold != null && hold.isAfter(now)) {
                throw new StockGateRejectedException(ErrorCode.ALREADY_IN_CHECKOUT,
                        "You already have a checkout in progress for this item");
            }
            if (bought.getOrDefault(holdKey(line.menuItemId(), customerId), 0) + line.quantity() > maxUnitsPerUser) {
                throw new StockGateRejectedException(ErrorCode.PURCHASE_LIMIT,
                        "Limit of " + maxUnitsPerUser + " units per customer for this item");
            }
            if (stock.getOrDefault(line.menuItemId(), 0) < line.quantity()) {
                throw new StockGateRejectedException(ErrorCode.SOLD_OUT, "Sold out");
            }
        }
        Instant expiresAt = now.plus(ttl);
        for (GateLine line : lines) {
            stock.merge(line.menuItemId(), -line.quantity(), Integer::sum);          // DECRBY
            holds.put(holdKey(line.menuItemId(), customerId), expiresAt);            // SET NX EX
        }
        String id = UUID.randomUUID().toString();
        reservations.put(id, new Resv(customerId, List.copyOf(lines), expiresAt));  // HSET + ZADD resv:expiry
        return new GateReservation(id, customerId, List.copyOf(lines));
    }

    @Override
    public synchronized void confirm(GateReservation r) {
        if (r.isNone() || reservations.remove(r.id()) == null) {
            return; // already swept or released
        }
        for (GateLine line : r.lines()) {
            holds.remove(holdKey(line.menuItemId(), r.customerId()));
            bought.merge(holdKey(line.menuItemId(), r.customerId()), line.quantity(), Integer::sum);
        }
    }

    @Override
    public synchronized void release(GateReservation r) {
        if (r.isNone()) {
            return;
        }
        Resv resv = reservations.remove(r.id());
        if (resv != null) { // not swept yet: give the units back exactly once
            giveBack(resv.customerId(), resv.lines());
        }
    }

    @Override
    public synchronized void resync(long menuItemId, int mysqlStock) {
        ensureAvailable();
        int inFlight = reservations.values().stream()
                .flatMap(r -> r.lines().stream())
                .filter(l -> l.menuItemId() == menuItemId)
                .mapToInt(GateLine::quantity).sum();
        stock.put(menuItemId, Math.max(0, mysqlStock - inFlight));
    }

    @Override
    public synchronized int sweepExpired() {
        return sweepExpiredAt(clock.instant());
    }

    /** Test support: sweep as if at a given time. */
    public synchronized int sweepExpiredAt(Instant now) {
        int swept = 0;
        for (Iterator<Resv> it = reservations.values().iterator(); it.hasNext(); ) {
            Resv resv = it.next();
            if (!resv.expiresAt().isAfter(now)) {
                it.remove();
                giveBack(resv.customerId(), resv.lines());
                swept++;
            }
        }
        return swept;
    }

    private void giveBack(long customerId, List<GateLine> lines) {
        for (GateLine line : lines) {
            stock.merge(line.menuItemId(), line.quantity(), Integer::sum); // INCRBY
            holds.remove(holdKey(line.menuItemId(), customerId));
        }
    }

    // ---- simulation controls / inspection (tests, demos) ----

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public synchronized Integer counter(long menuItemId) {
        return stock.get(menuItemId);
    }

    public synchronized void reset() {
        stock.clear();
        holds.clear();
        bought.clear();
        reservations.clear();
        available = true;
    }

    private void ensureAvailable() {
        if (!available) {
            throw new StockGateUnavailableException("Redis unavailable");
        }
    }

    private static String holdKey(long itemId, long customerId) {
        return itemId + ":" + customerId;
    }
}
