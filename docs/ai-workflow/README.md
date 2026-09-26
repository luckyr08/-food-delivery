# AI workflow

This project was built with **Claude Code** (CLI) as a pair programmer. I owned every decision; the AI
proposed options, wrote code to the agreed design, and ran the tests. Goal of the workflow: be able to
explain and defend every line.

## The loop (every step, one commit each)
1. **Design first, no code.** The AI explains the requirement's hidden problem and lays out options with
   pros/cons tables and a recommendation.
2. **I decide.** I asked clarifying questions (e.g. "why not MySQL?", "isn't Docker out of scope?") and
   changed several recommendations (MySQL instead of PostgreSQL, real MySQL for tests instead of H2,
   JWT without email in the token, ...).
3. **Build** in layers, compiling after each.
4. **Verify**: unit + integration tests against real MySQL; real multi-threaded HTTP tests for every
   concurrency claim, repeated runs, deadlock/500 counts from logs.
5. **Document in the same commit**: an ADR (`docs/decisions`), README, and new rules in `CLAUDE.md`.
6. **Walkthrough, then I commit** after reading the diff.

## Artifacts
| File | Role |
|---|---|
| [`CLAUDE.md`](../../CLAUDE.md) | Working agreement and project rules the AI follows; grew as lessons were learned (e.g. the lock-order rule) |
| [`.claude/skills/`](../../.claude/skills) | Project skills (below) |
| [`docs/decisions/`](../decisions) | 12+ ADRs: every decision with alternatives, trade-offs and test evidence |
| `docs/ai-workflow/transcript.md` | Exported Claude Code session via `/export` (raw record of the collaboration; added at submission) |

## Skills
Steps 1–10 followed the loop above, captured in `CLAUDE.md`. After step 10 I packaged the loop and the
concurrency lessons into reusable **project skills** (commit `b5ca421`) and used them for the remaining work:

| Skill | Encodes | Used for |
|---|---|---|
| `feature-step` | design → options → decision → build → verify → ADR/README → handover | Step 11 onwards |
| `concurrency-proof` | latch-based race-test harness, invariants to assert, CAS vs optimistic vs atomic increment, lock-order rules from three real deadlocks, "prove it by reversing the order" | concurrency work after step 10 |
| `api-endpoint` | URL/role conventions, ownership query → 404 (IDOR), DTO records, ErrorCode, pagination limits, test checklist | new endpoints |
| `adr-writer` | ADR template with verification evidence | new ADRs |
| `flyway-migration` | never edit applied migrations, MySQL DDL caveats, `ddl-auto=validate` check | schema changes (V4, V5) |
| `search-sync` | what to index, outbox calls in every write path, versioning, outage/convergence tests, reindex runbook | Created for step 12 (search), then followed while building it |

Next: run Claude Code's built-in `/security-review` and `/code-review` skills on the finished code and
commit any fixes separately (this line is updated once done).

Natural next skills: `security-checklist` (IDOR / mass assignment), `readme-sync` (endpoint table from
controllers), `perf-review` (N+1 and `EXPLAIN` checks), `demo-seeder`, `release-notes`.

## Where the AI was wrong — and what caught it
Keeping this list honest was part of the process; each item is visible in the history.

| Mistake | Caught by | Fix |
|---|---|---|
| Stock deducted **after** inserting `order_items` → FK shared lock + upgrade → 130 deadlocks, 39 HTTP 500s with 50 buyers (no oversell, but failures) | `OrderConcurrencyTest` | X-lock stock first; ADR 0008 |
| Recommended lock order "order row → partner row" for claims (and I approved it) → deadlock when one partner claims several orders | `PartnerClaimConcurrencyTest` | Partner row first; ADR 0010 |
| Same pattern predicted for reviews | Proved by deliberately reversing the order: 96 deadlocks vs 0 | Increment before insert; ADR 0012 |
| Spring passes `body = null` for some MVC errors, so 404s lacked our `code` field | `GlobalExceptionHandlerTest` | Build the body in the hook |
| Email with spaces failed `@Email` before normalization | `AuthIntegrationTest` | Trim in the record's compact constructor |
| Used Java 21 pattern-matching `switch` on Java 17 | Compiler | `instanceof` chain |
| Controller calling a repository directly (broke our own layering rule) | Self-review against `CLAUDE.md` | `MenuBrowseService` |
| Skipped the README update in step 2 | Me | Rule added to `CLAUDE.md`: every step updates README |
| Proposed the outbox id as the search document version (ids aren't commit-ordered → lost updates) | Writing the `search-sync` skill | Per-restaurant `search_version` bumped in the same transaction |
| Charged the payment gateway inside the placement transaction (locks held during a network call, "charged but no order") | Reviewer challenge after step 13 | Payment saga + reconciler + refunds via outbox (ADR 0015) |
| Outbox relay under REPEATABLE READ deadlocked with parallel relays; DB errors misread as index failures | `SearchSyncConcurrencyTest` | READ COMMITTED relay; narrower catch |
| Test-only issues: invalid assertion, a Python edit script broken by Java text blocks, bash brace expansion in a curl smoke script | Compiler / test output | Fixed before commit |

## What I'd tell another engineer
- Make the AI present options before code; the decision record is as valuable as the code.
- Every concurrency claim needs a real concurrent test — two of three lock-order designs were wrong until tested.
- Turn repeated lessons into project rules and skills so they're applied automatically next time.
