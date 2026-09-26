# 0016 — Redis stock gate for hot items (configurable; MySQL stays the source of truth)

**Status:** accepted

## Decision
`app.stock-gate.mode`:
- `mysql-only` (default) — MySQL's conditional `UPDATE ... WHERE stock >= q` does everything.
- `redis` — items marked `flash_sale` (V7) are admitted through a Redis gate first; flash-sale losers get
  `409 SOLD_OUT` in ~1 ms and never reach MySQL. **MySQL remains the source of truth and the final guard.**

Port `StockGate`, adapters `MysqlOnlyStockGate` and `SimulatedRedisStockGate` (in-memory, synchronized =
one Lua script; TTLs on the injected Clock). Real scripts: `src/main/resources/redis/{reserve,release,sweep}.lua`.

## Keys and scripts
| Key | Purpose |
|---|---|
| `stock:{item}` | Units the gate may hand out (loaded from MySQL on first use, SET NX) |
| `hold:{item}:{user}` (NX + TTL) | One active checkout per customer per hot item |
| `bought:{item}:{user}` | Per-customer cap per sale (default 2) |
| `resv:{id}` + `resv:expiry` (sorted set) | Reservation + expiry index for the sweeper |

`reserve.lua` is atomic over all lines: hold → cap → stock → DECRBY → reservation + TTL.

## Flow and failure handling
reserve (Redis) → MySQL transaction (conditional UPDATE still decides) → confirm (commit: MySQL's deduction is now
the reservation) · MySQL failed or replay → release (INCRBY back) · crash before commit → sweeper returns units after
the TTL (30 s) · order later cancelled/declined/expired → MySQL restock + outbox event → counter re-synced from MySQL ·
periodic re-sync every 60 s · Redis down → **fail open** to MySQL (correct, just slower; bulkhead still protects).

## Why drift is safe
The gate can only be wrong in two directions: it admits a buyer MySQL rejects (→ 409 as without the gate), or it says
sold out while MySQL has stock (→ temporary lost sale, fixed by re-sync). It can never cause an oversell.

## Verified
`FlashSaleGateConcurrencyTest`: 300 buyers, 50 units → 50 × 201, 250 × `SOLD_OUT`, zero `INSUFFICIENT_STOCK` (no
loser reached MySQL), MySQL and gate both 0; same customer 5× at once → at most the cap. `StockGateIntegrationTest`:
per-customer cap, declined payment returns units (MySQL, then gate via outbox), abandoned reservation swept back,
gate down → fail open, gate wrong → MySQL still refuses. Default mode: the full suite runs unchanged.

## Trade-offs
Extra moving part and consistency work (sweeper, re-sync); simulated adapter is per JVM (a real Redis is shared);
counter can be briefly pessimistic (commit→confirm window).
