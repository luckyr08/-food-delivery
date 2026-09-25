# 0009 — Order lifecycle state machine

**Status:** accepted

## Transitions (`OrderStateMachine`)
| From → To | Role | Side effects |
|---|---|---|
| PLACED → ACCEPTED | Owner | — |
| PLACED → REJECTED | Owner (reason required) | Restock + payment reversal |
| ACCEPTED → PREPARING | Owner | — |
| PREPARING → READY_FOR_PICKUP | Owner | — |
| READY_FOR_PICKUP → OUT_FOR_DELIVERY | Delivery partner | (step 8) |
| OUT_FOR_DELIVERY → DELIVERED | Delivery partner | (step 8) |
| PLACED / ACCEPTED → CANCELLED | Customer, Admin | Restock + payment reversal |
| PREPARING / READY_FOR_PICKUP / OUT_FOR_DELIVERY → CANCELLED | Admin (reason required) | Payment reversal, **no restock** |

Final: DELIVERED, REJECTED, CANCELLED. Unknown transition → 409 `INVALID_STATUS_TRANSITION`;
known transition, wrong role → 403 `TRANSITION_NOT_ALLOWED_FOR_ROLE`.

## Decisions
| Decision | Why | Alternative |
|---|---|---|
| Transition table in plain Java (`EnumMap` of from → to → roles) | One source of truth; exhaustively unit-tested (8×8×4 combinations) | Spring Statemachine (heavy), State pattern (8 classes for a lookup table) |
| One transition method (`OrderLifecycleService.transition`) for all roles | Check, change, side effects and history can't diverge between callers | — |
| Optimistic locking (`@Version`) for concurrent transitions | Conflicts are rare (two humans on one order); loser rolls back incl. side effects → 409 | Compare-and-set UPDATE fits hot contention (partner assignment, step 8); `FOR UPDATE` for long read-decide-write |
| Flush right after the status change | The version check runs before any side effect | — |
| Restock only if cancelled/rejected from PLACED or ACCEPTED | After PREPARING the ingredients are used | Always restock (would inflate stock) |
| Restock adds to the current stock | Simple and atomic (`stock = stock + q`) | If the owner reset stock absolutely in between, the count can overshoot; owners' counts are estimates |
| Payment reversal inside the transaction, last step: SUCCESS → gateway refund → REFUNDED; COD PENDING → VOIDED | A failed refund fails the cancellation, so nothing is half-done | After-commit refund + outbox (production): cancellation never blocked by the gateway |
| Owner uses one `PATCH .../status` endpoint; cancel is its own command | The state machine validates; fewer endpoints | One command endpoint per action |
| Restaurant queue sorted oldest first | FIFO kitchen | — |
| Admin can cancel any non-final order with a reason | Support override | — |

## Verified
`OrderConcurrencyTest.cancelVsRejectRace`: customer cancel and owner reject fired together on the same
PLACED order, 10 rounds → exactly one 200 and one 409 per round, stock restored exactly once; the 409s
came from the optimistic lock (true overlaps).

## Not built
Auto-reject when a restaurant doesn't respond within N minutes (`@Scheduled` job over PLACED orders).
