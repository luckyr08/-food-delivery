-- Gateways deliver webhooks at least once: every processed event id is stored so a redelivery has no effect.
CREATE TABLE payment_webhook_event (
    event_id        VARCHAR(100) NOT NULL,
    event_type      VARCHAR(40)  NOT NULL,
    order_reference VARCHAR(64)  NULL,
    received_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_id)
);
