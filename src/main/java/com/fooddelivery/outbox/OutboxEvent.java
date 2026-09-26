package com.fooddelivery.outbox;

/** A claimed outbox row (only what the relay needs). */
public record OutboxEvent(long id, String aggregateType, long aggregateId, String eventType, int attempts) {
}
