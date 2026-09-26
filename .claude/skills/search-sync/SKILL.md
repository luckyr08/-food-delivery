---
name: search-sync
description: Keep the search index (Elasticsearch port, in-memory adapter) in sync with MySQL without losing updates — decide what to index, enqueue outbox events in every write path in the right lock order, version documents, test convergence and outages, and reindex safely. Use when adding/changing an indexed field or entity, touching a write path of indexed data, changing the mapping, or debugging stale search results.
---

# Search sync (MySQL → search index)

MySQL is the source of truth. The index is a derived, eventually consistent read model fed by a
transactional outbox. Never write to the index from a request thread.

## 1. Should the field be indexed?
Index only what search needs AND changes rarely (names, cuisine, prices, veg, owner toggles, open flag,
rating). Never index per-order data like stock: it would re-index on every sale and still be stale.
Real-time checks (stock at order time) stay in MySQL.

## 2. Every write path that changes an indexed field
Call `OutboxWriter.restaurantChanged(id)` (or `cityChanged(cityId)`) inside the SAME transaction:
- It bumps `restaurants.search_version` and inserts an `outbox_event` row — both commit or neither.
- Call it BEFORE modifying entities in that transaction: the bump X-locks the restaurant row first, so
  paths that later lock menu_items (and placement, which S-locks the restaurant via the orders FK) can't
  form a lock cycle (see `concurrency-proof` skill).
- `search_version` is mapped `insertable = false, updatable = false`: entity saves never overwrite it.
- Add an integration test per write path: exactly one outbox row appears (and none for non-indexed
  changes such as stock).

## 3. Relay and projection (already built — extend, don't bypass)
- `OutboxRelay` claims due rows with `FOR UPDATE SKIP LOCKED` (safe with several instances), groups by
  aggregate (coalescing), calls the `OutboxHandler`, marks processed; failures → attempts++, exponential
  backoff (cap 5 min), `last_error`. Rows are never dropped.
- `SearchProjector` re-reads CURRENT state from MySQL (one transaction snapshot) and upserts with
  `version = search_version` (external versioning): stale writes are rejected, so order of processing and
  duplicate processing don't matter. Not publicly visible → versioned delete.
- Do not use the outbox id as the document version: ids are assigned at insert, not commit, so they are
  not commit-ordered.

## 4. Mapping changes / drift
- Real Elasticsearch mapping lives in `src/main/resources/search/restaurants-mapping.json`.
- Change = new index version + full reindex + alias swap (`POST /api/admin/search/reindex`). During the
  rebuild, live upserts go to both the old and the new index so nothing written meanwhile is lost.
- The in-memory adapter rebuilds from MySQL on startup.

## 5. Tests to add or update
- Per-path outbox test (above); convergence: after `relay.drain()` the document equals the DB state.
- Outage: `InMemorySearchIndex.setAvailable(false)` → rows stay pending with attempts/last_error, search
  falls back to MySQL; restore, make rows due, drain → converged (no update lost).
- Coalescing: N edits of one restaurant → one document write.
- Concurrency (`concurrency-proof`): edits racing orders → 0 deadlocks; parallel relays → each row once.

## 6. Swapping in real Elasticsearch
Implement `SearchIndex` with the ES Java client: `bulk` with `version_type=external`, delete with version,
alias-based reindex; query = bool filter (cityId, cuisine, open) + multi_match (name^3, cuisine^2) +
nested query on `menu.name` with `fuzziness: AUTO` and `inner_hits`. No business code changes.

## Runbook
- Search stale: `GET /api/admin/search/status` → pending/retrying counts, index availability.
- Rows stuck with `last_error`: fix the cause; they retry automatically (backoff ≤ 5 min).
- Suspected drift (manual DB edits): `POST /api/admin/search/reindex`.
