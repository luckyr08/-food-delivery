---
name: feature-step
description: Deliver one feature of this project as a single reviewable step — design with options and trade-offs, get the developer's decision, implement, test (incl. concurrency where relevant), write the ADR, update the README, stage for the developer to commit. Use whenever starting a new feature, endpoint group or cross-cutting change in this repo.
---

# Feature step

The developer must be able to defend every line in an interview. Never jump to code.

## 1. Design (no code yet)
Present, in this order:
1. What the requirement really asks (quote the brief if relevant) and the hidden engineering problem.
2. Endpoints / data changes as a table.
3. Each real decision as an options table: option | pros | cons, with one marked as recommended and why.
4. Edge cases and what is deliberately NOT built (goes to README "Assumptions" / ADR "Not built").
5. The test plan: unit, integration, concurrency (use the `concurrency-proof` skill when two actors can touch the same row).
6. A numbered list of decisions for the developer. Stop and wait.

## 2. Build
- Follow CLAUDE.md architecture rules and the `api-endpoint` skill for endpoints.
- Schema changes only via the `flyway-migration` skill.
- Compile after each layer (`./mvnw -q compile`); fix before moving on.

## 3. Verify
- Run the new tests, then the full suite: `./mvnw -q test`, then sum surefire results.
- Concurrency or async tests: run them at least 3 times; report deadlock / 500 counts from the log.
- If a test fails, diagnose from logs before changing code; if the design was wrong, say so explicitly.

## 4. Document (same commit)
- ADR via the `adr-writer` skill (`docs/decisions/NNNN-*.md`).
- README: endpoint table, relevant section, assumptions, testing notes.
- CLAUDE.md: add any new rule learned (e.g. lock ordering).

## 5. Hand over
- `git add -A`, show `git status --short`. Do NOT commit unless the developer says so.
- Walk through the code: why each non-obvious line exists, deviations from the design and why,
  honest limitations. Suggest the commit message.
