-- SHA-256 (hex) of the placement request, stored with the idempotency key so a replay with the
-- same key but a different body can be rejected instead of silently returning the old order.
ALTER TABLE orders ADD COLUMN idempotency_request_hash CHAR(64) NULL AFTER idempotency_key;
