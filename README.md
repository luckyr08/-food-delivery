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

## Design decisions
All decisions with alternatives and trade-offs: [`docs/decisions/`](docs/decisions).

## Seeded data
- Admin: `admin@fooddelivery.com` / `Admin@123` (created by migration `V2`; change outside local dev).

## Assumptions
- One order contains items from a single restaurant.
- `menu_items.stock = NULL` means unlimited; a number means limited units that can't go below 0.
- The delivery address is captured as text on each order (no saved address book).
- Cities, restaurants and menu items are soft-deleted (`active = false`) because past orders reference them.
- Money is stored with 2 decimal places in a single currency (INR).

## API overview
_TBD_

## AI workflow
Developed with Claude Code. Working agreement: [`CLAUDE.md`](CLAUDE.md).
