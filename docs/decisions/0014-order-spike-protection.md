# 0014 — Protecting the database during 10–20x order spikes

**Status:** accepted (protection built; capacity measures documented)

## Problem
Search moved reads off MySQL, but every order is a write transaction. Under a spike: buyers queue on the hot
item's row lock → each waiter holds one of 20 pooled connections → the pool runs dry → requests wait 30 s
(Hikari default) → clients retry → more load. More app instances make it worse (more connections).
Principle: **protect the DB first, then reduce work per order, then add capacity.**

## Built
| Measure | Detail |
|---|---|
| Bulkhead (`OrderAdmissionControl`) | Fair `Semaphore`, 16 concurrent placements per instance (< pool of 20); wait ≤ 200 ms for a slot, then `503 SERVICE_BUSY` + `Retry-After: 2`. A plain semaphore because Spring 7's `@ConcurrencyLimit` blocks callers instead of failing fast. |
| Per-customer rate limit (`OrderRateLimiter`) | Token bucket, 5 burst, 5/min refill → `429 RATE_LIMITED` + `Retry-After`. In memory (per instance); production: gateway or Redis. Checked before the bulkhead (cheapest first). |
| Fail-fast timeouts | Hikari `connection-timeout` 2 s; `innodb_lock_wait_timeout` 5 s (default 50 s). |
| Lock failures → 503 | `PessimisticLockingFailureException` that survives the retry maps to 503 + `Retry-After`, not 500. |

## Verified — `OrderSpikeTest`
200 customers order one item at the same instant on a warm server, bulkhead squeezed to 4:
16–17 × 201, ~184 × 503 with `Retry-After`, **peak 4 placements in the DB, 0 × 500, no oversell,
slowest response < 1 s** (3 runs). A cold server (first requests after boot) admitted only 4 — JIT and
connection warm-up; pre-warming before a known festival matters.

## Production roadmap (not built)
| Measure | Why | Trade-off |
|---|---|---|
| Payment Saga (ADR 0008) | Row locks held ms instead of the gateway's 1–3 s — biggest win with a real gateway | More states, compensation |
| Redis stock gate (atomic `DECRBY`/Lua before MySQL; release on DB failure) | Losers of a flash sale rejected in ~1 ms without touching MySQL | Redis + reconciliation |
| Stock split into N bucket rows | Spreads one hot row lock | Summing, uneven buckets |
| Async intake: publish to Kafka → `202 Accepted` (PENDING) → workers write at a steady rate | Constant DB load regardless of spike | Async UX, Kafka |
| Shard orders by `city_id` (Vitess) | Orders never cross cities; spikes are regional | Operational complexity |
| ProxySQL, read replicas, date-partitioned `orders` | Connections decoupled from instances; primary kept for writes | Infra, replica lag |
| Load tests (k6/Gatling), pre-scaling, feature flags to shed non-essentials, circuit breakers | Known capacity, graceful degradation | Process |
