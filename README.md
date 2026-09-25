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

## Assumptions
_TBD_

## API overview
_TBD_

## AI workflow
Developed with Claude Code. Working agreement: [`CLAUDE.md`](CLAUDE.md).
