# 0011 — Asynchronous notification fan-out

**Status:** accepted

## Decision
Order changes publish an `OrderEvent` inside their transaction (placement, every lifecycle transition,
partner claim). `OrderNotificationListener` handles it with
`@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async("notificationExecutor")`.

| Annotation | Guarantees | Without it |
|---|---|---|
| `AFTER_COMMIT` | Runs only if the transaction committed | A declined payment would still notify "order placed" for a rolled-back order |
| `@Async` | Runs on the notification pool | `AFTER_COMMIT` alone runs on the HTTP thread before the response is sent; a slow provider slows every request |

- The event holds ids and plain values, not entities (the listener runs on another thread after the
  persistence context closed; lazy fields would throw).
- Publishing requires an active transaction (`fallbackExecution` is false); all publishers are transactional.

## Recipients and channels
- Everyone involved — customer, restaurant owner, assigned partner — **except the actor** (their HTTP
  response is their confirmation). Placement → owner; owner actions → customer (+ partner); claim → customer + owner.
- In-app notification row (`notifications`, own short transaction per recipient) + `NotificationSender`
  (logging stub; the seam for FCM/SMS/email).
- Per-recipient try/catch: one failing recipient never stops the fan-out.

## Executor
Core 4, max 8, queue 1000, threads `notify-*`, graceful drain on shutdown.
Full queue → `CallerRunsPolicy`: the publishing thread does the work (slower under overload, natural
backpressure, nothing dropped). Alternative: abort/discard + log (never slows callers, drops notifications).

## Delivery guarantee — honest limitation
In-memory events are **at most once**: a crash between commit and the listener loses them.

| Option | Guarantee | Cost |
|---|---|---|
| **After-commit event + @Async (built)** | Best effort | Simple |
| Transactional outbox: `outbox_event` row in the same transaction; `@Scheduled` relay reads with `SELECT ... FOR UPDATE SKIP LOCKED` (safe with several instances), fans out, marks done | At least once (consumers must be idempotent) | Migration, poller, retries |
| Message broker (Kafka/RabbitMQ) fed by the outbox | At least once, scalable consumers | Distributed systems are out of scope |

## Testing
- Slow sender (2 s) → the order request still returns in < 1 s; the notification arrives later.
- Declined payment → zero notifications.
- Listener threads are `notify-*`; a failing recipient doesn't stop the others.
- Test isolation: every integration/concurrency test waits for the notification pool to be idle
  afterwards, so async writes from one test can't leak into the next test's clean database.
