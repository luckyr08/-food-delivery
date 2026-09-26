-- Bumped in the same transaction as every change to indexed restaurant/menu/city data; used as the
-- search document's external version so stale index writes are rejected (outbox ids are not commit-ordered).
ALTER TABLE restaurants ADD COLUMN search_version BIGINT NOT NULL DEFAULT 0 AFTER rating_count;
