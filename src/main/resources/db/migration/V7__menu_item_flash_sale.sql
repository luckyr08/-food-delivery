-- Hot items (flash sales) can be gated in Redis before MySQL when app.stock-gate.mode=redis.
ALTER TABLE menu_items ADD COLUMN flash_sale BOOLEAN NOT NULL DEFAULT FALSE AFTER stock;
