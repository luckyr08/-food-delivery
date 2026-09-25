# 0007 — Menu management, ownership checks and browsing

**Status:** accepted

## Decisions
| Decision | Why | Trade-off / alternative |
|---|---|---|
| Role-prefixed URLs (`/api/owner/**`, public `/api/restaurants/**`) | One URL rule per role; role-specific DTOs (owners see stock, customers don't) | Same resource under two paths |
| Ownership = `findByIdAndOwnerId` (one query) → 404 | Other owners' restaurants are indistinguishable from missing ones | — |
| Nested item lookup `findByIdAndRestaurantId` after the restaurant ownership check | Prevents IDOR: own restaurant id + competitor's item id | — |
| Admin has no menu-edit API | Menus belong to owners; admin's lever is deactivating the restaurant | — |
| Stock via dedicated `PUT .../stock`, absolute value, `null` = unlimited, field required | PATCH `null` would be ambiguous (unchanged vs unlimited); owners count what's on hand | Delta updates would never conflict but don't model "unlimited" |
| Stock update saved through the entity (`@Version`) | A sale committed between read and write → 409 instead of overwriting it | Protects only the server-side read→write window; cross-request protection would need the client to send the version (ETag/If-Match) |
| Soft-delete menu items | `order_items` reference them | Every query filters `active` |
| Customers see only a computed `available` flag | Exact stock is business-sensitive | No "only N left" hint |
| Sold-out items stay in the public menu (`available: false`) | Matches real apps (greyed out) | — |
| Browsing requires `cityId`; open restaurants first | City-scoped like real apps; uses the `(city_id, active)` index | — |
| Name search: contains-LIKE with escaped `%`, `_`, `\`; case-insensitive via collation | Correct literal search; no `LOWER()` needed | Leading `%` can't use an index (bounded by city); FULLTEXT/Elasticsearch at scale |
| Public menu not paginated | Menus are small and shown in full | — |
| No caching yet | Keep scope | Menus are read-heavy: Spring Cache + Redis with eviction on owner edits is the scale-up path |
