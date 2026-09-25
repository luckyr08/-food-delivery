# 0001 — Stack, database and test strategy

**Status:** accepted

## Decision
Java 17, Spring Boot 4.1 (only line offered by Spring Initializr), Maven wrapper,
MySQL 8 for both running the app and running tests. No Docker.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| PostgreSQL + Testcontainers | Transactional DDL, partial indexes | Needs Docker; brief says containerization is out of scope |
| H2 for tests, MySQL for app | Zero-setup tests | H2 locking is not InnoDB; concurrency tests become less convincing |
| **MySQL for app and tests** | Oversell/contention tests prove real InnoDB behaviour | Reviewer must create two schemas (one script: `scripts/db-setup.sql`) |

## Consequences
- Tests need a real MySQL; setup is documented in README.
- Tests reset the schema with Flyway clean+migrate instead of transaction rollback,
  because concurrency tests need committed transactions on multiple threads.
- DDL in MySQL is not transactional: a failed migration can be half-applied. Keep migrations small.
- InnoDB default isolation is REPEATABLE READ; `UPDATE` is a locking read of the latest committed
  row, so conditional updates (`WHERE stock >= ?`) are safe under it.
- App connects as least-privilege user `food_app` limited to the two schemas.
