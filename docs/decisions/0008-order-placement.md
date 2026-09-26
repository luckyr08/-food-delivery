# 0008 — Order placement: atomicity, overselling, payment, idempotency

**Status:** accepted — payment handling superseded by [ADR 0015](0015-payment-saga.md) (saga, no gateway call inside the transaction)

## Flow (one transaction, `OrderPlacementTx.place`)
1. Idempotent replay if the `Idempotency-Key` was already used by this customer
2. Reject duplicate lines
3. Restaurant must be visible and open
4. Items must exist, belong to the restaurant, be switched on (friendly early checks)
5. Price from the DB (the request has no prices)
6. INSERT the order row (claims the idempotency key)
7. Deduct stock with one conditional UPDATE per item, ascending item id
8. INSERT order items (name/price snapshot) and the initial status history
9. Charge payment last; decline → 402 and everything rolls back

`OrderService.placeOrder` wraps it from outside (not transactional): Spring 7 `@Retryable` on
`PessimisticLockingFailureException` (max 2 retries), and replay when a concurrent duplicate loses the
idempotency UNIQUE-key race.

## Overselling
```sql
UPDATE menu_items SET stock = CASE WHEN stock IS NULL THEN NULL ELSE stock - :q END, version = version + 1
WHERE id = :id AND active AND available AND (stock IS NULL OR stock >= :q)   -- 0 rows => 409
```
Row lock + locking read of the latest committed row (even under REPEATABLE READ) + check + write in one
statement. Also re-validates `active/available` (closes the gap after step 4's unlocked read) and bumps
`version` so a concurrent owner stock edit fails with 409. `CHECK (stock >= 0)` is the last line of defence.

| Alternative | Why not |
|---|---|
| `SELECT ... FOR UPDATE` then check in Java | Holds the lock longer; more round trips |
| `@Version` read-modify-save + retry | Under a flash sale most attempts fail and retry |
| Redis `DECR` | Extra infrastructure; DB and cache can diverge |

## Deadlock found by the concurrency test (and fixed)
The first version inserted `order_items` **before** deducting stock. The FK `order_items → menu_items`
makes InnoDB take a **shared** lock on the item row; the stock UPDATE then needs an **exclusive** lock.
Two transactions both holding S and each waiting to upgrade to X = deadlock — even for single-item orders,
so sorted locking alone did not help. 50 concurrent buyers produced 130 deadlocks and 39 HTTP 500s
(the retry absorbed ~90 but cannot fix a systematic deadlock).

**Fix:** insert the order row (FKs lock only `restaurants`/`users`), then take the X locks via the stock
UPDATE, then insert `order_items`; their FK check hits locks we already own. Result: 0 deadlocks over
repeated runs. Rule: *take the exclusive lock on a parent row before inserting children that reference it.*

## Payment
| Option | Pros | Cons |
|---|---|---|
| **A. Charge inside the transaction (chosen)** | Literal atomicity; simple | Row locks held during the gateway call (fine for the in-process mock, not for 1–3 s real latency); charge can succeed and commit fail |
| B. Saga: reserve + `PENDING_PAYMENT` → commit → charge → confirm or compensate | No locks during network calls; crash-tolerant | More states, a sweeper for stuck orders, eventual consistency |

Mitigations built into A: charge is the last step; a `TransactionSynchronization` refunds an approved
charge if the transaction rolls back afterwards (mini-compensation); COD creates a `PENDING` payment
without calling the gateway. B is the production path.

## Idempotency
- Optional `Idempotency-Key` header, unique per customer (`UNIQUE (customer_id, idempotency_key)`).
- Replay → `200` + `Idempotent-Replayed: true` with the original order; nothing is charged or deducted.
- Same key, different body → `409 IDEMPOTENCY_KEY_REUSED` (SHA-256 request hash, migration V3).
- Concurrent duplicates: the order row is inserted before stock is touched; the second INSERT blocks on the
  unique index, fails after the first commits, and `OrderService` returns the first order.

## Other decisions
- Duplicate lines → 400 (not silently merged). 1–50 lines, quantity 1–20.
- Delivery fee and free-delivery threshold are configuration (`app.order.*`).
- Insufficient-stock message has no exact count: after the failed UPDATE, a plain SELECT would read this
  transaction's REPEATABLE READ snapshot, not the value the UPDATE saw.
- Customer reads are ownership-scoped queries (`findWithItemsByIdAndCustomerId`) → 404 for others' orders.
