package com.fooddelivery.outbox;

import java.util.Set;

/** A consumer of outbox events for one aggregate type. Must be idempotent (at-least-once delivery). */
public interface OutboxHandler {

    String aggregateType();

    /** Called inside the relay's transaction with the distinct aggregate ids of a batch. */
    void handle(Set<Long> aggregateIds);
}
