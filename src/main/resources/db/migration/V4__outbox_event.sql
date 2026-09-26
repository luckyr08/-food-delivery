-- Transactional outbox: rows are written in the same transaction as the business change and processed
-- asynchronously by OutboxRelay (at-least-once). Generic so other consumers (e.g. notifications) can use it.
-- No FKs: the aggregate may be deleted/deactivated and the event must still be processed.
CREATE TABLE outbox_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(40)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    payload         VARCHAR(2000) NULL,
    created_at      DATETIME(6)  NOT NULL,
    next_attempt_at DATETIME(6)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(500) NULL,
    processed_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- The relay's claim query: WHERE processed_at IS NULL AND next_attempt_at <= ? ORDER BY id
    INDEX idx_outbox_pending (processed_at, next_attempt_at, id)
);
