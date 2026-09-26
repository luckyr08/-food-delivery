# 0013 — Search index (Elasticsearch port) synced by a transactional outbox

**Status:** accepted

## Decision
Customer search (typos, dish names, relevance, autocomplete) is served from a search index; MySQL stays the
source of truth. The index is behind a `SearchIndex` port shaped after Elasticsearch. The app ships a
**simulated Elasticsearch** (`InMemorySearchIndex`: external versioning + tombstones, fuzzy AUTO matching,
nested dish hits, scoring, filters, autocomplete, alias-swap rebuild, outage switch). The real mapping is in
`src/main/resources/search/restaurants-mapping.json`; a real adapter needs no business-code changes.

## Document model
One `restaurants` document per publicly visible restaurant, menu as a `nested` array, city fields embedded
(no city index — a few dozen rows). **No stock**: it changes on every order (write amplification, always
stale); real-time availability is checked in MySQL at order time.

| Alternative | Why not |
|---|---|
| Separate menu_items index | Restaurant changes fan out to hundreds of item docs |
| Parent/child join | Slow, complex queries |

## Sync: transactional outbox
| Option | Problem |
|---|---|
| Dual write in the service | Rollback after index write, or index write fails after commit → silent drift |
| After-commit event + @Async | Lost on crash or index outage |
| **Outbox + relay (chosen)** | — |
| CDC (Debezium → Kafka → ES sink) | Best at scale; needs Kafka/Debezium (out of scope) — production path |

- `OutboxWriter` (propagation MANDATORY) bumps `restaurants.search_version` and inserts `outbox_event`
  in the business transaction, **before** entities are modified (lock order below). Called from all 8
  indexed write paths; not for stock or orders (tested).
- `OutboxRelay` (scheduled, 1 s): claim due rows `FOR UPDATE SKIP LOCKED` → group per restaurant
  (coalescing) → `SearchProjector` re-reads current state in one snapshot → versioned upsert/delete →
  mark processed. Handler failure → attempts++, backoff 2^n s (cap 5 min), `last_error`; never dropped.
- **Version = `search_version`, not the outbox id** (a flaw caught while writing the skill): ids are
  assigned at insert, not commit, so a lower id can commit later; using it would reject a newer state.
- `search_version` is mapped `insertable/updatable = false`, so entity saves never overwrite it.
- Rebuild (`POST /api/admin/search/reindex`, and on startup since the index is in memory): new index,
  live writes go to both, atomic swap.
- Search falls back to MySQL name search when the index is down (`source: database-fallback`).

## Verified (tests found two problems)
- `SearchSyncIntegrationTest`: every indexed write path enqueues exactly one event, stock/orders none;
  convergence (doc version = DB search_version); deactivation removes; **outage: rows retried, fallback
  served, recovery converges — no update lost**; 20 edits → 1 index write.
- `SearchSyncConcurrencyTest`:
  - 4 relays in parallel over 300 events → every event claimed exactly once. First version deadlocked:
    REPEATABLE READ gap locks from `FOR UPDATE` vs processed-marking. **Fix: relay runs READ COMMITTED.**
    Also fixed: a DB error while marking processed was being treated as an index failure.
  - 20 menu edits racing 20 orders → 0 deadlocks (bump-first lock order), index converges to the DB.
    Finding: 18 of 20 edits get 409 because each sale bumps the item's `@Version` (ADR 0007). Correct per
    the current design, but poor UX during rushes; option: update non-stock fields with a targeted
    non-versioned UPDATE, keep versioning only for the stock endpoint.
- 5 repeated runs, 0 deadlocks, 0 HTTP 500s.

## Limitations
In-memory index: rebuilt on startup and one copy per app instance (real ES is shared). Search is eventually
consistent (~1 s). Notifications still use in-memory events; moving them onto this outbox is the next step.
