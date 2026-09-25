# 0006 — Admin onboarding, updates and pagination

**Status:** accepted

## Decisions
| Decision | Why | Trade-off / alternative |
|---|---|---|
| Owner onboarding in two calls (owner account, then restaurant with `ownerId`) | Owner ↔ restaurant is 1:N; a second branch reuses the same owner | One combined call is fewer requests but still needs a second endpoint for branches |
| Delivery partner = user + profile in one call and one transaction | Partner ↔ user is 1:1; no half-created partner | — |
| Admin sets the initial password | Simplicity | Production: invite / password-reset email |
| `UserService.createUser` shared by registration and admin onboarding | One place for email normalization, duplicate check, hashing | — |
| PATCH with nullable fields (null = unchanged) | Partial updates | Can't express "set to null"; not needed for these fields (JSON Merge Patch would allow it) |
| Own `PageResponse<T>` instead of serializing Spring's `Page` | `PageImpl` JSON isn't a stable contract | — |
| `size` limited to 1..100 via `@Max` (400 above) | Prevents huge pages; explicit to clients | Silently clamping would hide client bugs |
| Server-side fixed sort, no client `sort` param | A client-chosen sort on e.g. `owner.passwordHash` would leak data via ordering | Less flexible |
| Offset pagination | Admin lists are small | Deep pages are slow; keyset pagination for feeds |
| `@EntityGraph(owner, city)` on list/detail queries | Avoids N+1 (1 query instead of 1 + 2N); verified in SQL logs | Only to-one associations: a collection fetch + pagination would page in memory |
| Optional filters as `(:p IS NULL OR col = :p)` JPQL | One readable query for two filters | Specifications/Criteria for many filters |
| `@PreAuthorize("hasRole('ADMIN')")` on admin controllers in addition to the URL rule | Defence in depth if URL mappings change | — |
| Soft-delete doesn't cascade: deactivating a city leaves its restaurants untouched | Browse queries filter by `city.active`; no mass updates | Every browse query must join the city |
| Deactivating a restaurant also closes it | An inactive restaurant must not accept orders | — |
| Admins cannot deactivate themselves | Avoid locking out the last admin | — |
