# 0015 — Payment as a saga (no external calls inside DB transactions)

**Status:** accepted — supersedes the payment part of ADR 0008

## Why the first version was wrong
ADR 0008 charged the gateway as the last step inside the placement transaction ("option A"). With a real
gateway that is a design flaw:

| Problem | Effect |
|---|---|
| Row locks + a pooled connection held during a 1–3 s network call | A hot item serialises at one order per gateway round-trip; the pool drains under spikes (undoes ADR 0014) |
| Charge succeeds, commit fails | Customer charged, no order; the refund-on-rollback hook was only a patch (fails if the refund or the process fails) |
| Timeout = unknown outcome | We rolled back although the customer may have been charged |
| Refunds called the gateway inside the cancel transaction | Same problems for cancellations |

## Decision
```
Tx1 (ms):   validate, reserve stock, order PAYMENT_PENDING, payment INITIATED → COMMIT (locks released)
outside tx: gateway.charge(ref = order id)          ← idempotent on the order id
Tx2 (ms):   approved → payment SUCCESS, order PLACED, restaurant notified
            declined → payment FAILED, order CANCELLED, stock released (compensation) → 402
            unknown  → stay PAYMENT_PENDING → 202 Accepted
```
- `PaymentReconciler` (every 60 s): PAYMENT_PENDING orders older than 60 s → ask the gateway
  "was order X charged?" → yes: complete; no and older than 15 min: cancel + release stock; gateway
  unreachable: leave it (never cancel blind).
- Refunds: the cancel transaction sets `REFUND_PENDING` + an outbox event; `PaymentRefundHandler` calls the
  gateway after commit via the relay, retrying with backoff; idempotent on the provider reference.
- New `OrderStatus.PAYMENT_PENDING` (hidden from restaurants; only `SYSTEM` may resolve it), `PaymentStatus`
  `INITIATED` and `REFUND_PENDING`, pseudo-role `SYSTEM` (never assigned to an account).
- Cash on delivery: no gateway, PLACED in Tx1.
- **Webhook** `POST /api/payments/webhook` (the primary confirmation for asynchronous methods like UPI and
  3-D Secure): HMAC-SHA256 over `timestamp.rawBody` with a shared secret, constant-time compare, 5-minute
  timestamp window (replay protection); event ids stored in `payment_webhook_event` (V6) so redeliveries are
  no-ops; `payment.captured` / `payment.failed` / `refund.processed`. A capture arriving after we cancelled
  the order is refunded automatically via the outbox; a captured amount that doesn't match the order total
  → 422, order left pending. Webhook, synchronous response and reconciler converge on the same idempotent
  steps; `PaymentWebhookRaceTest` (webhook + reconciler at the same instant, 10 rounds) → exactly one
  completion each.

## Verified (`PaymentSagaIntegrationTest`)
The gateway mock asserts no transaction is active while it is called; decline → CANCELLED + FAILED + stock
restored + no notifications; timeout → 202, reconciler completes it (charged) or cancels it after expiry
(not charged), leaves young orders and never cancels while the gateway is unreachable; refund retries through
the outbox until the gateway is back. All earlier concurrency tests still pass (195 tests).

## Trade-offs
More states and code; stock is reserved while payment is pending (bounded by the 15-minute expiry); the
customer may see "payment processing" (202) instead of an immediate answer.
