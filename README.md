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

## Design decisions
See [`docs/decisions/`](docs/decisions).

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
