---
name: concurrency-proof
description: Prove a concurrency claim (no oversell, single winner, counted once, no deadlock) with a real multi-threaded test against MySQL, and choose the right locking technique. Use when two or more actors can write the same row, for any "atomic", "exactly once" or "contention" requirement, or when a deadlock appears.
---

# Concurrency proof

## Choose the technique
| Situation | Technique |
|---|---|
| Hot contention on one row (stock, claim, counters) | Single conditional UPDATE (compare-and-set); check affected rows; 0 → 409 |
| Rare conflicts, read-decide-write (status transitions) | `@Version` optimistic lock; flush before side effects |
| Aggregates (sum/count) | Atomic increment `x = x + ?` |
| Exactly-once creation (idempotency, one review) | UNIQUE constraint; map duplicate-key to the domain 409 |

Any hand-written UPDATE on a versioned entity must also `version = version + 1`, otherwise a stale entity
update (Hibernate writes all columns) silently overwrites it.

## Lock-order rules (learned the hard way in this repo)
1. Multi-row locks in ascending id order.
2. X-lock a parent row BEFORE writing a child that references it (insert, or setting the FK column):
   the FK check takes an S lock and concurrent S→X upgrades deadlock. (ADR 0008, 0010, 0012)
3. `@Retryable` on `PessimisticLockingFailureException` wraps the transaction from OUTSIDE and is a safety
   net only — never the fix for a systematic deadlock.

## Write the test
- Extend `support/ConcurrencyTestBase` (RANDOM_PORT server, real HTTP, JDBC setup, JWTs minted directly).
- `runConcurrently(n, i -> ...)`: all threads wait on one `CountDownLatch`, released together.
- Assert the invariant on final DB state, not only status codes:
  exact counts (e.g. 10 × 201, 40 × 409), stock/sum values, row counts, "exactly one BUSY partner".
- No `@Transactional` on the test (threads need committed data).

## Verify
- Run ≥ 3 times; grep the log: `Deadlock found` count and `Unhandled exception` (500) count must be 0.
- For a lock-order claim, prove it: temporarily reverse the order, run once, record the deadlock count,
  restore, re-run. Put the numbers in the ADR.
- If deadlocks appear, find the statement in the log (`LockAcquisitionException ... [SQL]`) and look for an
  FK-induced shared lock before blaming ordering between rows.
