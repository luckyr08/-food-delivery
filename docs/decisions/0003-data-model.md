# 0003 — Data model

**Status:** accepted

## Decisions
| Decision | Why | Trade-off |
|---|---|---|
| `BIGINT AUTO_INCREMENT` ids | InnoDB clusters rows by PK; sequential inserts append | Guessable ids → ownership checks prevent IDOR |
| Money as `DECIMAL(10,2)` / `BigDecimal` | No floating-point rounding errors | — |
| Enums as `VARCHAR` + `@Enumerated(STRING)` | ORDINAL breaks on reorder; MySQL `ENUM` needs ALTER to extend | Slightly larger rows |
| `order_items` snapshot `item_name`, `unit_price` | Menu edits must not change past bills | Duplicated data |
| `menu_items.stock` nullable (NULL = unlimited) | Real menus don't count every item | Guard becomes `stock IS NULL OR stock >= ?` |
| `CHECK (stock IS NULL OR stock >= 0)` | DB-level last line of defence against oversell bugs | Needs MySQL ≥ 8.0.16 |
| Ratings as `rating_sum` + `rating_count` | Single atomic `UPDATE ... + ?`; no read-modify-write | Average computed on read |
| Soft delete (`active`) on city/restaurant/menu item | Orders reference them via FK | Every browse query filters `active` |
| `payments` separate from `orders` | Refund on cancel/reject; room for retries | Extra join |
| `UNIQUE (customer_id, idempotency_key)` | Duplicate-order protection that can't be raced | Key is optional (NULLs allowed) |
| `UNIQUE reviews.order_id` | One review per order even on double submit | — |
| `order_status_history` | Audit trail + tracking timeline | Extra insert per transition |
| Delivery address as text snapshot on the order | Scoping: address book is plain CRUD | No saved addresses |
| `@Version` on orders, menu_items, delivery_partners, restaurants | Lost-update safety net for normal edits | Hand-written conditional UPDATEs must also bump `version` |
| All `@ManyToOne` LAZY; only `Order → items` is `@OneToMany` | Avoid N+1 and huge object graphs | Services must fetch what they need (open-in-view off) |
| No Lombok `@Data` on entities | Generated equals/hashCode/toString touch lazy relations (proxies, recursion) | Explicit `@Getter/@Setter` |
