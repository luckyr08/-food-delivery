package com.fooddelivery.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Moves outbox events to their handlers. One batch = one transaction:
 * claim (FOR UPDATE SKIP LOCKED) → group by aggregate (coalescing) → handle → mark processed,
 * or on failure mark for retry with backoff. Delivery is at-least-once; handlers are idempotent.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final Map<String, OutboxHandler> handlers;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, List<OutboxHandler> handlers, PlatformTransactionManager txManager,
                       @Value("${app.outbox.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::aggregateType, Function.identity()));
        this.tx = new TransactionTemplate(txManager);
        // READ COMMITTED: under REPEATABLE READ the claim's FOR UPDATE takes gap locks on the pending index,
        // and marking rows processed moves index entries into those gaps -> parallel relays deadlock
        // (found by SearchSyncConcurrencyTest). The usual isolation for SKIP LOCKED queue tables.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.batchSize = batchSize;
    }

    /** @return number of events claimed in this batch (0 = nothing due). */
    public int processBatch() {
        Integer claimed = tx.execute(status -> {
            List<OutboxEvent> events = outbox.claimBatch(batchSize);
            events.stream().collect(Collectors.groupingBy(OutboxEvent::aggregateType))
                    .forEach(this::dispatch);
            return events.size();
        });
        return claimed == null ? 0 : claimed;
    }

    /** Processes batches until nothing is due. Failed rows get a future next_attempt_at, so this ends. */
    public int drain() {
        int total = 0;
        int claimed;
        while ((claimed = processBatch()) > 0) {
            total += claimed;
        }
        return total;
    }

    private void dispatch(String aggregateType, List<OutboxEvent> events) {
        List<Long> eventIds = events.stream().map(OutboxEvent::id).toList();
        OutboxHandler handler = handlers.get(aggregateType);
        if (handler == null) {
            log.warn("No outbox handler for {}; marking {} events processed", aggregateType, eventIds.size());
            outbox.markProcessed(eventIds);
            return;
        }
        // Coalescing: 50 edits of one restaurant in a batch become one handle call for that id.
        Set<Long> aggregateIds = events.stream().map(OutboxEvent::aggregateId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            handler.handle(aggregateIds);
        } catch (RuntimeException e) {
            // Only handler (index) failures are retried with backoff. A DB failure below is NOT caught:
            // the whole batch rolls back and the rows are simply claimed again by the next poll.
            log.warn("Outbox handler {} failed for {} events, will retry: {}", aggregateType, eventIds.size(),
                    e.getMessage());
            outbox.markFailed(eventIds, e.getMessage());
            return;
        }
        outbox.markProcessed(eventIds);
    }
}
