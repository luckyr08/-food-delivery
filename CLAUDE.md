# CLAUDE.md — AI working agreement for this repo

Take-home: Food Delivery Order Management (Spring Boot). The developer must be able to
explain every line in an interview, so the workflow is deliberately slow.

## Workflow
- One step = one commit. Before writing code for a step, explain the design, alternatives,
  and pros/cons, and wait for the developer's go-ahead.
- After writing code, walk through it. The developer reviews the diff and commits themselves.
- Record every non-trivial decision as an ADR in `docs/decisions/NNNN-title.md`.
- Every step updates README.md (new endpoints, assumptions, setup changes) in the same commit.
- Never add features beyond the agreed step.

## Stack (locked)
- Java 17, Spring Boot 4.1, Maven wrapper (`./mvnw`; Maven is not installed globally).
- MySQL 8 (InnoDB, REPEATABLE READ) for app AND tests — no Docker/Testcontainers
  (containerization is out of scope per the brief). Schemas: `food_delivery`, `food_delivery_test`.
- Flyway owns the schema (`ddl-auto: validate`). Never edit an applied migration; add a new one.
- JWT (jjwt) with a custom `OncePerRequestFilter`. Stateless; no sessions.

## Architecture rules
- Package-by-feature under `com.fooddelivery` (`order`, `menu`, `restaurant`, ...), each with
  controller → service → repository. Shared code in `common`.
- Controllers accept/return DTOs (Java records), never JPA entities.
- Lombok only on entities. `open-in-view` is off: load what you need inside the service transaction.
- Authorization = role check (URL/`@PreAuthorize`) + ownership check in the service layer.
- Errors go through the global `@RestControllerAdvice` with one consistent error JSON shape.

## Concurrency rules
- Contended counters (stock, ratings aggregates) are changed with a single conditional
  `UPDATE ... WHERE <guard>` and the affected-row count is checked. No read-modify-save.
- Multi-row locking happens in ascending id order to avoid deadlocks.
- Take the exclusive lock on a parent row (e.g. the stock UPDATE on menu_items) BEFORE inserting child rows
  that reference it: the child's FK check takes a shared lock, and S→X upgrades deadlock (see ADR 0008).
- Retry (`@Retryable` on PessimisticLockingFailureException) wraps the transaction from outside and is only
  a safety net, never the fix for a systematic deadlock.
- "First writer wins" claims (partner assignment) use conditional UPDATE; losers get 409.
- Side effects (notifications) run after commit: `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`.

## Testing
- Unit tests: plain JUnit + Mockito for services/state machine.
- Integration tests: `@SpringBootTest` + MockMvc against real MySQL, profile `test`.
- Concurrency tests use real threads and committed transactions (no `@Transactional` rollback).
- Run: `./mvnw test`.
