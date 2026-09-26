---
name: flyway-migration
description: Change the MySQL schema safely with a new Flyway migration and matching JPA entity changes. Use for any new table, column, index or constraint.
---

# Flyway migration

1. Never edit an applied migration (`V1`..`Vn` in `src/main/resources/db/migration`): Flyway stores a
   checksum and startup fails. Always add `V{n+1}__snake_case_description.sql`.
2. MySQL DDL is not transactional: keep each migration small, one concern per file, so a failure can't
   leave a half-applied multi-step change.
3. Conventions: `BIGINT AUTO_INCREMENT` ids, `DECIMAL(10,2)` money, enums as `VARCHAR`, `DATETIME(6)` UTC,
   named constraints (`fk_`, `uk_`, `chk_`, `idx_`), an index for every query you add, `CHECK` constraints
   as the last line of defence.
4. Update the entity in the same change; `ddl-auto: validate` makes the context test fail on any mismatch —
   run `./mvnw -q test -Dtest=FoodDeliveryApplicationTests,SchemaMigrationTest` first.
5. New tables must be cleaned by `support/DatabaseCleaner` (it discovers tables automatically; check seed
   rows that must survive).
6. Mention the migration in the ADR and README data-model table.
