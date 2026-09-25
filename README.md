# Food Delivery Order Management

Spring Boot backend for a multi-city food delivery platform: restaurants and menus, order
placement without overselling, order lifecycle, delivery-partner assignment under contention,
async status notifications, and ratings.

> Work in progress — sections are filled in as features land.

## Tech stack
Java 17 · Spring Boot 4.1 · Spring Data JPA (Hibernate) · Spring Security + JWT · MySQL 8 · Flyway · JUnit 5 / Mockito / MockMvc

## Running locally

Prerequisites: Java 17+, MySQL 8 running on `localhost:3306`. Maven is not needed (use `./mvnw`).

```bash
# 1. One-time DB setup (creates food_delivery, food_delivery_test and user food_app)
mysql -u root -p < scripts/db-setup.sql

# 2. Run tests (uses food_delivery_test)
./mvnw test

# 3. Run the app (uses food_delivery)
./mvnw spring-boot:run
```

Override connection settings with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`.

## Architecture
- **Package-by-feature** (`order`, `menu`, `restaurant`, `delivery`, `rating`, `notification`, `user`, `city`),
  each with controller → service → repository; shared code in `common`.
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

## Ratings & reviews
Customers review a delivered order once, within 7 days: a required restaurant rating and an optional
delivery-partner rating. Averages are kept as `rating_sum`/`rating_count` updated with atomic increments
in the same transaction as the review, so concurrent reviews are never lost and a rejected duplicate never
counts. Public reviews show only the reviewer's first name; partner ratings stay private
([ADR 0012](docs/decisions/0012-ratings-and-reviews.md)).

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

## Design decisions
All decisions with alternatives and trade-offs: [`docs/decisions/`](docs/decisions).

## Seeded data
- Admin: `admin@fooddelivery.com` / `Admin@123` (created by migration `V2`; change outside local dev).

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

## AI workflow
Developed with Claude Code. Working agreement: [`CLAUDE.md`](CLAUDE.md).
