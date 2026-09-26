# Food Delivery Order Management

Spring Boot backend for a multi-city food delivery platform: cities, restaurants and menus; customer
ordering with **no overselling under concurrency**; an explicit **order lifecycle state machine**;
**delivery-partner assignment** where many partners contend for one order; **asynchronous
notifications** delivered only after commit; and **ratings & reviews**.

**Highlights**
- Atomic order placement (stock + order + payment in one transaction), proven with 50 concurrent buyers
  for 10 units → exactly 10 orders.
- Three deadlocks found by real concurrency tests and fixed at the root cause (see
  [Concurrency & consistency](#concurrency--consistency)).
- 12 ADRs documenting every decision with alternatives and evidence; 160+ tests against real MySQL.
- Built with Claude Code using an explicit design → decide → build → verify → document loop
  ([AI workflow](#ai-workflow)).

## Contents
- [Quick start](#quick-start)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Data model](#data-model)
- [Authentication & roles](#authentication--roles)
- [Order lifecycle](#order-lifecycle)
- [Concurrency & consistency](#concurrency--consistency)
- [Notifications (asynchronous fan-out)](#notifications-asynchronous-fan-out)
- [Ratings & reviews](#ratings--reviews)
- [Search (Elasticsearch port, outbox sync)](#search-elasticsearch-port-outbox-sync)
- [Error handling](#error-handling)
- [API overview](#api-overview)
- [Testing](#testing)
- [Assumptions](#assumptions)
- [Scaling to production](#scaling-to-production)
- [Design decisions](#design-decisions)
- [AI workflow](#ai-workflow)

## Quick start
Prerequisites: **Java 17+** and **MySQL 8** on `localhost:3306`. No Maven install needed (`./mvnw`),
no Docker (containerization was out of scope).

```bash
# 1. One-time DB setup: creates food_delivery, food_delivery_test and the least-privilege user food_app
mysql -u root -p < scripts/db-setup.sql

# 2. Run all tests (uses food_delivery_test; ~2 min, includes multi-threaded tests)
./mvnw test

# 3. Run the app with sample data (uses food_delivery)
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Open **Swagger UI: http://localhost:8080/swagger-ui.html**, call `POST /api/auth/login`, click
**Authorize** and paste the `accessToken`.

| Demo login | Password | Role |
|---|---|---|
| `admin@fooddelivery.com` | `Admin@123` | Admin (seeded by migration `V2`, always present) |
| `owner1@demo.com` | `Password@123` | Restaurant owner — Spice Hub, Dosa Corner (Pune) |
| `owner2@demo.com` | `Password@123` | Restaurant owner — The Bowl Co (Bengaluru) |
| `partner1@demo.com`, `partner2@demo.com` | `Password@123` | Delivery partners, Pune, AVAILABLE |
| `partner3@demo.com` | `Password@123` | Delivery partner, Bengaluru |
| `customer@demo.com` | `Password@123` | Customer |

The `demo` profile seeds through the real services (validation, hashing, rules apply) and is idempotent.
"Chicken Biryani" at Spice Hub has only 5 in stock — try ordering 6.

Without `demo`, only the admin exists. Override connection settings with `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SECRET`.

## Tech stack
| Choice | Why |
|---|---|
| Java 17, Spring Boot 4.1 (Spring MVC) | Required stack; blocking MVC matches blocking JPA/JDBC — WebFlux would add complexity for no gain |
| MySQL 8 (InnoDB) for app **and** tests | Concurrency guarantees (row locks, locking reads) are tested on the real engine, not an emulation |
| Spring Data JPA / Hibernate 7 + hand-written conditional UPDATEs | JPA for normal CRUD; single-statement SQL where atomicity under contention matters |
| Flyway | Versioned, reviewable schema; Hibernate only validates |
| Spring Security + JWT (jjwt, HS256) | Stateless auth, role checks + ownership checks |
| Spring events + `@Async` | Async notification fan-out without extra infrastructure |
| Spring Framework 7 `@Retryable` | Built-in retry for deadlock/lock-timeout, outside the transaction |
| JUnit 5, Mockito, MockMvc, Awaitility | Unit, web-slice, integration, concurrency and async tests |
| springdoc-openapi | Swagger UI for reviewers |

## Architecture
- **Package-by-feature**: `auth`, `user`, `city`, `restaurant`, `menu`, `order`, `payment`, `delivery`,
  `rating`, `notification`, each with controller → service → repository; `security` (JWT filter, config),
  `common` (errors, pagination, config), `demo` (sample data, `demo` profile only).
- Every order status change goes through one method (`OrderLifecycleService.transition`) guarded by one
  transition table (`OrderStateMachine`); side effects (restock, refund, partner release, events) live there.
- Controllers exchange DTOs (Java records), never JPA entities. `open-in-view` is disabled.
- Schema is owned by Flyway migrations; Hibernate only validates it (`ddl-auto: validate`).

## Data model
| Table | Purpose |
|---|---|
| `users` | All accounts; `role` = ADMIN / RESTAURANT_OWNER / CUSTOMER / DELIVERY_PARTNER |
| `cities` | Cities the platform operates in |
| `restaurants` | Owned by a restaurant owner, located in a city; `is_open` (owner) vs `active` (admin soft delete) |
| `menu_items` | Per restaurant; `price`, `available` toggle, `stock` (NULL = unlimited) |
| `delivery_partners` | Partner profile linked 1:1 to a user; city, vehicle, `status` (OFFLINE/AVAILABLE/BUSY) |
| `orders` | Customer + restaurant + optional partner, `status`, amounts, delivery address, idempotency key |
| `order_items` | Line items with **name/price snapshot** taken at order time |
| `order_status_history` | Audit trail of every status change (tracking timeline) |
| `payments` | One per order; SUCCESS / FAILED / REFUNDED |
| `reviews` | One per delivered order; restaurant rating + optional partner rating |
| `notifications` | In-app notifications produced asynchronously from order events |

Key choices: `DECIMAL` money, `BIGINT` ids, enums stored as strings, soft deletes, rating aggregates as
`rating_sum`/`rating_count` for atomic updates, DB-level `CHECK` and `UNIQUE` constraints as the last line
of defence, `@Version` optimistic locking on contended rows. Details: [ADR 0003](docs/decisions/0003-data-model.md).

## Authentication & roles
Stateless JWT (HS256, 60-minute expiry). Swagger UI: http://localhost:8080/swagger-ui.html
(use **Authorize** and paste the `accessToken`).

```bash
# register (always creates a CUSTOMER)
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Asha","email":"asha@example.com","password":"secret123"}'

# login -> {"accessToken":"...","tokenType":"Bearer","expiresIn":3600,"userId":2,"role":"CUSTOMER"}
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"secret123"}'

# call a protected endpoint
curl localhost:8080/api/users/me -H "Authorization: Bearer <accessToken>"
```

| Role | How the account is created | Can |
|---|---|---|
| ADMIN | Seeded | Manage cities, restaurants, delivery partners (`/api/admin/**`) |
| RESTAURANT_OWNER | By admin | Manage own restaurant's menu, accept/reject its orders |
| CUSTOMER | Self-registration | Browse, order, track, rate |
| DELIVERY_PARTNER | By admin | Accept assignments, update delivery status |

Authorization has three layers: URL rules (e.g. `/api/admin/**` → ADMIN), `@PreAuthorize` role checks per
endpoint, and **ownership checks in services** (an owner can only touch their own restaurant, a customer
only their own orders). Browsing (`GET /api/cities/**`, `GET /api/restaurants/**`) is public.

## Order lifecycle
```
PLACED ──► ACCEPTED ──► PREPARING ──► READY_FOR_PICKUP ──► OUT_FOR_DELIVERY ──► DELIVERED
  │  │         │             (owner)          (owner)            (partner)          (partner)
  │  └─────────┴──► CANCELLED  (customer before PREPARING; admin any time before DELIVERED)
  └──► REJECTED (owner, with reason)
```
- Rules live in one transition table (`OrderStateMachine`): invalid move → `409 INVALID_STATUS_TRANSITION`,
  wrong role → `403 TRANSITION_NOT_ALLOWED_FOR_ROLE`.
- Reject/cancel refunds the payment (`REFUNDED`, or `VOIDED` for cash on delivery). Stock is returned
  only if the food wasn't cooked yet (cancelled from PLACED/ACCEPTED).
- Every change is recorded in `order_status_history`, which powers the customer's timeline.
- **Delivery partners** claim orders in their city from ACCEPTED onwards (so they can travel while the food
  is prepared). A claim sets the partner BUSY; delivery or cancellation makes them AVAILABLE again.
  Cash-on-delivery payments are marked SUCCESS on delivery. Customers see the assigned partner's name,
  phone and vehicle on their order.
- Simultaneous changes to one order (e.g. customer cancels while the restaurant rejects) are resolved by
  optimistic locking: one wins, the other gets `409` and none of its side effects apply.

## Concurrency & consistency
- **No overselling:** stock is decremented with a single conditional `UPDATE ... WHERE stock >= :qty`
  (row lock + check + write in one statement); 0 rows affected → `409 INSUFFICIENT_STOCK`.
- **Atomic placement:** order, items, stock and payment are written in one transaction; any failure
  (including a declined payment) rolls everything back.
- **Deadlock-free:** items are locked in ascending id order, and stock rows are X-locked before
  `order_items` (whose FK would otherwise take a shared lock first — a deadlock our concurrency test found).
  Deadlocks/lock timeouts are still retried from outside the transaction as a safety net.
- **Idempotent placement** with an optional `Idempotency-Key`, safe under concurrent duplicates.
- **Partner contention:** claims use two compare-and-set UPDATEs (partner `AVAILABLE → BUSY`, then order
  `delivery_partner_id IS NULL → partner`) in one transaction, so exactly one partner gets an order and a
  partner never holds two ([ADR 0010](docs/decisions/0010-delivery-assignment.md)).
- **Payment** is charged last inside the transaction (mock gateway); an approved charge is refunded if
  the transaction rolls back afterwards. A Saga with `PENDING_PAYMENT` is the production design
  ([ADR 0008](docs/decisions/0008-order-placement.md)).
- **Proven by `OrderConcurrencyTest`** (real server, real HTTP, real MySQL): 50 customers race for
  10 units → exactly 10 orders, 40 × 409, final stock 0; opposite item order from 40 threads → no
  deadlocks; 10 identical requests with one idempotency key → one order, stock deducted once;
  customer cancel vs restaurant reject on the same order (10 rounds) → one winner each, stock restored once.
  `PartnerClaimConcurrencyTest`: 20 partners claim one order → exactly 1 winner, 19 × 409; one partner claims
  5 orders at once → exactly 1 succeeds.
  `ReviewConcurrencyTest`: 30 simultaneous reviews of one restaurant → count 30 and exact sum, no deadlocks;
  the same review submitted 5 times at once → counted once.
- **Lock-ordering rule** (found by these tests, applied three times): take the exclusive lock on a parent row
  before writing a child row that references it — the FK check otherwise takes a shared lock first and
  concurrent S→X upgrades deadlock.

## Notifications (asynchronous fan-out)
Every order change (placement, each status transition, partner assignment) publishes an event inside its
transaction. A listener annotated `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` runs it on a
dedicated `notify-*` thread pool **only after the transaction commits**, so:
- the HTTP request never waits for notifications (proved by a test with a 2-second sender);
- rolled-back work (e.g. a declined payment) never produces notifications.

The customer, restaurant owner and assigned partner are notified — everyone involved except whoever made
the change. Each gets an in-app notification (`/api/notifications`) and a push via `NotificationSender`
(a logging stub). Delivery is best effort; a transactional outbox is the production upgrade
([ADR 0011](docs/decisions/0011-async-notifications.md)).

## Ratings & reviews
Customers review a delivered order once, within 7 days: a required restaurant rating and an optional
delivery-partner rating. Averages are kept as `rating_sum`/`rating_count` updated with atomic increments
in the same transaction as the review, so concurrent reviews are never lost and a rejected duplicate never
counts. Public reviews show only the reviewer's first name; partner ratings stay private
([ADR 0012](docs/decisions/0012-ratings-and-reviews.md)).

## Search (Elasticsearch port, outbox sync)
Typo-tolerant search over restaurant names, cuisine **and dishes** (`/api/search/restaurants?cityId=1&q=biryni`),
with the dishes that matched, plus autocomplete (`/api/search/suggest`).

- **Index:** behind a `SearchIndex` port shaped after Elasticsearch. The app runs a **simulated
  Elasticsearch** (in-memory adapter with ES semantics: external versioning, fuzzy matching, nested dish
  hits, scoring, alias-swap rebuild). The real index mapping is in
  `src/main/resources/search/restaurants-mapping.json`; no Elasticsearch install is needed.
- **One document per restaurant with its menu nested**; stock is deliberately not indexed.
- **No lost updates:** every change to indexed data writes an `outbox_event` row and bumps
  `restaurants.search_version` in the same transaction. A relay (every 1 s, `FOR UPDATE SKIP LOCKED`,
  READ COMMITTED) re-reads the current state and upserts it with `version = search_version`, so retries,
  duplicates and out-of-order processing are harmless. Index down → events retry with backoff and search
  falls back to MySQL.
- **Operations:** `GET /api/admin/search/status` (backlog, retries, rejected stale writes),
  `POST /api/admin/search/reindex`, and `PUT /api/admin/search/simulated-availability` to demo an outage.

Details and test evidence: [ADR 0013](docs/decisions/0013-search-index-and-outbox.md).

## Error handling
Every error is an [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) response
(`application/problem+json`) with a stable machine-readable `code`:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request has 1 invalid field(s)",
  "instance": "/api/auth/register",
  "code": "VALIDATION_FAILED",
  "timestamp": "2026-09-26T10:15:30Z",
  "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
}
```

| Status | When |
|---|---|
| 400 | Invalid input (validation, malformed JSON, wrong parameter type) |
| 401 | Missing, invalid or expired token |
| 403 | Authenticated but role not allowed |
| 404 | Not found — also returned for resources owned by someone else, so their existence isn't leaked |
| 409 | Valid request but the current state forbids it (out of stock, invalid status transition, concurrent update) |
| 500 | Unexpected error; generic message, details only in server logs |

Details: [ADR 0004](docs/decisions/0004-error-handling.md).

## API overview
| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register a customer |
| POST | `/api/auth/login` | Public | Get an access token |
| GET | `/api/users/me` | Authenticated | Own profile |
| GET | `/api/cities` | Public | Active cities |
| POST | `/api/admin/cities` | Admin | Create city (`name` unique, case-insensitive) |
| PATCH | `/api/admin/cities/{id}` | Admin | Rename / change state / activate-deactivate |
| GET | `/api/admin/cities` | Admin | All cities incl. inactive |
| POST | `/api/admin/restaurant-owners` | Admin | Create a restaurant-owner account |
| POST | `/api/admin/restaurants` | Admin | Create a restaurant for an owner in a city (starts closed) |
| PATCH | `/api/admin/restaurants/{id}` | Admin | Update details / activate-deactivate |
| GET | `/api/admin/restaurants?cityId=&active=&page=&size=` | Admin | Paginated search |
| POST | `/api/admin/delivery-partners` | Admin | Create partner account + profile (starts OFFLINE) |
| PATCH | `/api/admin/delivery-partners/{id}` | Admin | Change city / vehicle |
| GET | `/api/admin/delivery-partners?cityId=&status=&page=&size=` | Admin | Paginated search |
| PATCH | `/api/admin/users/{id}/status` | Admin | Block / unblock any account |
| GET | `/api/owner/restaurants` | Owner | My restaurants |
| PATCH | `/api/owner/restaurants/{id}/status` | Owner | Open / close (`{"open":true}`) |
| GET | `/api/owner/restaurants/{id}/menu-items` | Owner | My menu incl. unavailable items, with stock |
| POST | `/api/owner/restaurants/{id}/menu-items` | Owner | Add item (`stock` omitted/null = unlimited) |
| PATCH | `/api/owner/restaurants/{id}/menu-items/{itemId}` | Owner | Update name, description, category, price, veg, available |
| PUT | `/api/owner/restaurants/{id}/menu-items/{itemId}/stock` | Owner | Set stock (`{"stock":25}` or `{"stock":null}` = unlimited) |
| DELETE | `/api/owner/restaurants/{id}/menu-items/{itemId}` | Owner | Remove item (soft delete) |
| GET | `/api/restaurants?cityId=&q=&cuisine=&openOnly=&page=&size=` | Public | Browse a city's restaurants (open first) |
| GET | `/api/restaurants/{id}` | Public | Restaurant details |
| GET | `/api/restaurants/{id}/menu?category=&vegOnly=` | Public | Menu with `available` flag (no stock numbers) |
| POST | `/api/orders` | Customer | Place an order (optional `Idempotency-Key` header) |
| GET | `/api/orders?page=&size=` | Customer | My orders, newest first |
| GET | `/api/orders/{id}` | Customer | My order with items and payment |
| POST | `/api/orders/{id}/cancel` | Customer | Cancel while PLACED/ACCEPTED (`{"reason":"..."}` optional) |
| GET | `/api/orders/{id}/timeline` | Customer | Tracking: status changes with timestamps and notes |
| GET | `/api/owner/restaurants/{id}/orders?status=&page=&size=` | Owner | Order queue, oldest first |
| GET | `/api/owner/restaurants/{id}/orders/{orderId}` | Owner | Order details |
| PATCH | `/api/owner/restaurants/{id}/orders/{orderId}/status` | Owner | `ACCEPTED`, `REJECTED` (reason required), `PREPARING`, `READY_FOR_PICKUP` |
| POST | `/api/admin/orders/{id}/cancel` | Admin | Cancel any non-final order (reason required) |
| GET | `/api/partner/me` | Partner | Own profile and status |
| PATCH | `/api/partner/me/status` | Partner | `AVAILABLE` / `OFFLINE` (not while on a delivery) |
| GET | `/api/partner/orders/available?page=&size=` | Partner | Unassigned orders in my city, oldest first |
| POST | `/api/partner/orders/{id}/claim` | Partner | Claim an order; first partner wins, others get `409 ORDER_ALREADY_ASSIGNED` |
| GET | `/api/partner/orders/current` | Partner | My active delivery (`204` if none) |
| PATCH | `/api/partner/orders/{id}/status` | Partner | `OUT_FOR_DELIVERY` (once ready) / `DELIVERED` |
| POST | `/api/orders/{id}/review` | Customer | Rate a delivered order: `restaurantRating` 1–5, optional `partnerRating`, `comment` |
| GET | `/api/orders/{id}/review` | Customer | My review of an order |
| GET | `/api/restaurants/{id}/reviews?page=&size=` | Public | Restaurant reviews, newest first (first name only) |
| GET | `/api/search/restaurants?cityId=&q=&cuisine=&vegOnly=&openOnly=&page=&size=` | Public | Typo-tolerant search incl. dishes |
| GET | `/api/search/suggest?cityId=&q=` | Public | Autocomplete |
| POST | `/api/admin/search/reindex` | Admin | Rebuild the index from MySQL |
| GET | `/api/admin/search/status` | Admin | Index and outbox health |
| PUT | `/api/admin/search/simulated-availability` | Admin | Simulate search outage (`{"available":false}`) |
| GET | `/api/notifications?unreadOnly=&page=&size=` | Authenticated | My notifications, newest first |
| PATCH | `/api/notifications/{id}/read` | Authenticated | Mark one as read |
| POST | `/api/notifications/read-all` | Authenticated | Mark all as read |

### Placing an order
```bash
curl -X POST localhost:8080/api/orders -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 5f1c2a9e-order-1' \
  -d '{"restaurantId":1,"items":[{"menuItemId":1,"quantity":2}],
       "deliveryAddress":"Flat 4B, MG Road, Pune","paymentMethod":"UPI"}'
```
- `201` new order · `200` + `Idempotent-Replayed: true` for a repeated key · `409 INSUFFICIENT_STOCK` /
  `RESTAURANT_CLOSED` / `ITEM_UNAVAILABLE` / `IDEMPOTENCY_KEY_REUSED` · `402 PAYMENT_DECLINED`
- The request carries no prices; totals are computed server-side. Delivery fee 40.00, free from 500.00.

Paginated responses have the shape `{content, page, size, totalElements, totalPages}`; `page` starts at 0,
`size` is 1–100 (default 20). Sorting is fixed server-side.

### Admin onboarding flow
```bash
TOKEN=<admin accessToken>
# 1. city
curl -X POST localhost:8080/api/admin/cities -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Pune","state":"Maharashtra"}'
# 2. owner account, then the restaurant (ownerId/cityId from the previous responses)
curl -X POST localhost:8080/api/admin/restaurant-owners -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Ravi","email":"ravi@example.com","password":"secret123"}'
curl -X POST localhost:8080/api/admin/restaurants -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"ownerId":2,"cityId":1,"name":"Spice Hub","address":"MG Road","cuisine":"North Indian"}'
# 3. delivery partner (account + profile in one call)
curl -X POST localhost:8080/api/admin/delivery-partners -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Kiran","email":"kiran@example.com","password":"secret123","cityId":1,"vehicleType":"SCOOTER"}'
```

## Testing
- **Unit tests** (JUnit 5, no Spring): e.g. `JwtServiceTest` — expiry, tampered payload, wrong key, weak secret.
- **Web slice tests** (`@WebMvcTest`): error-contract mapping in `GlobalExceptionHandlerTest`.
- **Integration tests** (`@SpringBootTest` + MockMvc + real MySQL): extend `IntegrationTestBase`, which
  empties all tables before each test ([ADR 0005](docs/decisions/0005-integration-test-isolation.md)).
- **Concurrency tests** (`OrderConcurrencyTest`, `PartnerClaimConcurrencyTest`): real embedded server on a
  random port, many threads released together by a `CountDownLatch`, asserting final DB state.
- **Async tests** use Awaitility (poll until a condition holds) instead of sleeps; every test waits for the
  notification pool to be idle before the next one cleans the database.

## Assumptions
- One order contains items from a single restaurant.
- `menu_items.stock = NULL` means unlimited; a number means limited units that can't go below 0.
- The delivery address is captured as text on each order (no saved address book).
- Cities, restaurants and menu items are soft-deleted (`active = false`) because past orders reference them.
- Money is stored with 2 decimal places in a single currency (INR).
- Browsing cities, restaurants and menus doesn't require login; everything else does.
- Registration returns the profile only; the client then calls login to get a token.
- Tokens can't be revoked before expiry (60 min); a deactivated user's token keeps working until then.
- Restaurant owners and delivery partners are onboarded by an admin, who sets their initial password
  (production would use an invite / password-reset email).
- One owner can own several restaurants; a restaurant can't move to another city.
- New restaurants start closed (the owner opens them); new delivery partners start OFFLINE.
- Deactivating a city hides it and its restaurants from browsing but doesn't modify them; deactivating a
  restaurant also closes it. Orders already in progress are unaffected.
- A delivery partner's availability status is controlled by the partner, not the admin.
- Only the owner edits a menu; the admin controls restaurants by (de)activating them.
- Owners set stock as an absolute number ("25 left"); customers never see stock numbers, only whether an
  item is available. Sold-out items remain listed as unavailable.
- Accessing another owner's restaurant or menu item returns 404, never 403.
- An order contains items from one restaurant; the same item can't appear on two lines.
- Payment is simulated by an in-process mock gateway that approves every charge; cash on delivery
  creates a `PENDING` payment settled on delivery.
- Customers can cancel only before the restaurant starts preparing; admins can cancel later (refund, no restock).
- Partners claim orders themselves (no geo-based dispatch) and handle one order at a time; they can only
  see and claim orders in their own city.
- Notifications are in-app records plus a logged "push"; no real SMS/email/push provider is integrated.
  They are best effort (lost if the app crashes between commit and sending).
- Reviews are allowed once per delivered order, within 7 days, and can't be edited.
- A restaurant must give a reason to reject an order. Orders not handled by the restaurant stay PLACED
  (an auto-reject timeout job is a possible extension).
- Customers browse within one city (`cityId` is required).

## Scaling to production
What I'd change for real production load, roughly in order:

| Area | Now | Production |
|---|---|---|
| Notifications | In-memory after-commit events (best effort) | Move onto the outbox already built for search ([ADR 0013](docs/decisions/0013-search-index-and-outbox.md)), or outbox → Kafka |
| Payment | Charged inside the placement transaction (mock gateway) + refund on rollback | Saga: `PENDING_PAYMENT` → gateway → confirm / compensate; webhook reconciliation ([ADR 0008](docs/decisions/0008-order-placement.md)) |
| Browsing / menus | Direct DB reads; search via an ES-shaped port with an in-memory adapter, synced by an outbox | Real Elasticsearch adapter behind the same port; CDC (Debezium) instead of the outbox at larger scale; Redis cache for menus |
| Orders table | Single table | Partition/archive by date; move history to cheaper storage |
| Hot items (flash sales) | Row-lock serialisation on the item | Pre-sharded stock counters or Redis reservations reconciled to the DB |
| Assignment | Partners claim within their city | Geo-based dispatch (nearest partner, offer + timeout), ETA |
| Auth | 60-min access tokens, no revocation | Refresh tokens, revocation list / token version, rate limiting and lockout |
| Operations | Logs only | Metrics (queue depth, deadlock/409 rates), tracing, alerting — out of scope for this assignment |
| Deployment | Single instance | Stateless app behind a load balancer; all concurrency control is in the DB, so it scales horizontally unchanged |

## Design decisions
Every decision with the alternatives considered, trade-offs and verification evidence:

- [0001 — stack and database](docs/decisions/0001-stack-and-database.md)
- [0002 — jwt authentication](docs/decisions/0002-jwt-authentication.md)
- [0003 — data model](docs/decisions/0003-data-model.md)
- [0004 — error handling](docs/decisions/0004-error-handling.md)
- [0005 — integration test isolation](docs/decisions/0005-integration-test-isolation.md)
- [0006 — admin apis and pagination](docs/decisions/0006-admin-apis-and-pagination.md)
- [0007 — menu and browsing](docs/decisions/0007-menu-and-browsing.md)
- [0008 — order placement](docs/decisions/0008-order-placement.md)
- [0009 — order lifecycle](docs/decisions/0009-order-lifecycle.md)
- [0010 — delivery assignment](docs/decisions/0010-delivery-assignment.md)
- [0011 — async notifications](docs/decisions/0011-async-notifications.md)
- [0012 — ratings and reviews](docs/decisions/0012-ratings-and-reviews.md)

## AI workflow
Built with **Claude Code**. Summary: the AI proposed designs with options and trade-offs, I made each
decision, the AI implemented and tested it, and every step shipped with an ADR and README update in one
commit. `CLAUDE.md` holds the working agreement and the rules learned along the way; after step 10 the
process was packaged into project skills in [`.claude/skills/`](.claude/skills) and used for the rest.

Full description, skills, and an honest list of where the AI was wrong and what caught it:
[`docs/ai-workflow/README.md`](docs/ai-workflow/README.md).
