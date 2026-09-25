# 0005 — Integration test isolation

**Status:** accepted

## Decision
Integration tests extend `IntegrationTestBase` (`@SpringBootTest` + MockMvc + real MySQL
`food_delivery_test`). Before **each** test, every table except `flyway_schema_history` is emptied
(users keep only the seeded admin), with `FOREIGN_KEY_CHECKS = 0` on a single connection.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| `@Transactional` rollback per test | Automatic, fast | Concurrency tests need real commits from several threads, so two test styles would coexist; hides commit-time behaviour |
| Flyway clean + migrate per test | Always pristine | ~0.5–1 s per test |
| **Delete all rows before each test** | Fast; works for concurrency tests | Must preserve seed data (admin) explicitly |

## Consequences
- `SET FOREIGN_KEY_CHECKS` is session-scoped: the cleanup runs inside one `ConnectionCallback`,
  since separate `JdbcTemplate` calls may use different pooled connections.
- `DELETE` (not `TRUNCATE`) keeps it fast on tiny tables; ids keep growing, so tests never assume ids.
