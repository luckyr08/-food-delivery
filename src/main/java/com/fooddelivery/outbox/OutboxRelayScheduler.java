package com.fooddelivery.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox in the background. Disabled in tests, which call OutboxRelay.drain() directly. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay relay;
    private final OutboxRepository outbox;

    public OutboxRelayScheduler(OutboxRelay relay, OutboxRepository outbox) {
        this.relay = relay;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval-ms:1000}")
    void poll() {
        try {
            relay.drain();
        } catch (RuntimeException e) {
            log.error("Outbox relay poll failed", e); // keep the schedule alive; rows stay pending
        }
    }

    /** Processed rows are only history; keep a week. */
    @Scheduled(cron = "0 0 3 * * *")
    void purge() {
        log.info("Purged {} processed outbox events", outbox.purgeProcessedOlderThanDays(7));
    }
}
