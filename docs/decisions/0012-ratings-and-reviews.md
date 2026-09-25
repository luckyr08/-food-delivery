# 0012 — Ratings and reviews

**Status:** accepted

## Rules
| Rule | Enforcement |
|---|---|
| Only the order's customer | Ownership-scoped query → 404 |
| Only DELIVERED orders | 409 `ORDER_NOT_DELIVERED` |
| Once per order, even under double submit | `UNIQUE (order_id)` + pre-check → 409 `ALREADY_REVIEWED` |
| Restaurant rating 1–5 required, partner rating 1–5 optional, comment ≤ 1000 | Bean Validation + DB `CHECK` |
| Within `app.review.window-days` (7) of delivery | Delivery time from `order_status_history` → 409 `REVIEW_WINDOW_CLOSED` |
| Immutable | No edit endpoint (editing would need delta updates of the aggregates) |

## Aggregates
`rating_sum` / `rating_count` on restaurants and delivery partners, incremented atomically:
`UPDATE restaurants SET rating_sum = rating_sum + :r, rating_count = rating_count + 1, version = version + 1 ...`

| Alternative | Why not |
|---|---|
| `AVG()` over reviews on every read | O(n) per browse request for popular restaurants |
| Entity read-modify-save with `@Version` | Popular restaurant + concurrent reviews → constant optimistic failures |

- Increment and review insert are one transaction: a rejected duplicate rolls its increment back.
- `version` is bumped so an owner/admin entity update loaded earlier fails (409) instead of writing an old
  `rating_sum` back (Hibernate writes all columns).
- **Increment before insert** (parent X lock before the child's FK S lock — ADR 0008/0010). Verified by
  experiment: with the insert first, 30 concurrent reviews of one restaurant produced 96 deadlocks and 24
  failed requests; with the increment first, 0.

## Privacy
Public reviews show the reviewer's first name, rating, comment and date. Partner ratings are never public;
partners see their own average on `/api/partner/me`.

## Not built
Editing/deleting reviews, owner replies, moderation, notifying the owner of new reviews (an event, as in ADR 0011).
