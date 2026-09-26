package com.fooddelivery.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Called by services whenever data shown in search changes. MANDATORY: it must run inside the business
 * transaction so the change, the version bump and the outbox row commit together — or not at all.
 * Call it BEFORE modifying entities: the bump X-locks the restaurant row first (lock-order rule, ADR 0013).
 */
@Component
public class OutboxWriter {

    public static final String RESTAURANT = "RESTAURANT";
    public static final String PAYMENT = "PAYMENT";
    public static final String STOCK_GATE = "STOCK_GATE";
    static final String RESTAURANT_CHANGED = "RESTAURANT_CHANGED";

    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;

    public OutboxWriter(JdbcTemplate jdbc, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** A restaurant, its menu or its rating changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restaurantChanged(long restaurantId) {
        jdbc.update("UPDATE restaurants SET search_version = search_version + 1 WHERE id = ?", restaurantId);
        outbox.append(RESTAURANT, restaurantId, RESTAURANT_CHANGED);
    }

    /** A captured payment must be refunded; the gateway call happens after commit (PaymentRefundHandler). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentRefundRequested(long paymentId) {
        outbox.append(PAYMENT, paymentId, "REFUND_REQUESTED");
    }

    /** MySQL stock of a hot item went back up (cancel/decline): re-sync the Redis counter after commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stockGateResync(long menuItemId) {
        outbox.append(STOCK_GATE, menuItemId, "RESYNC");
    }

    /** A city's name or visibility changed: every restaurant document in it must be refreshed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cityChanged(long cityId) {
        jdbc.update("UPDATE restaurants SET search_version = search_version + 1 WHERE city_id = ?", cityId);
        outbox.appendForCityRestaurants(cityId, RESTAURANT_CHANGED);
    }
}
