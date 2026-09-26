---
name: adr-writer
description: Write an Architecture Decision Record in docs/decisions for a design decision in this project, using the repo's format (decision, options with trade-offs, consequences, verification evidence, not built). Use after any non-trivial design choice.
---

# ADR writer

File: `docs/decisions/NNNN-kebab-title.md`, next number after the highest existing one.

```markdown
# NNNN — Title

**Status:** accepted

## Decision
One paragraph: what we do and where in the code.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| **Chosen** | ... | ... |
| Alternative | ... | ... |

## Consequences
- What this forces elsewhere (tests, other code, operations).
- Honest limitations.

## Verified
Test names and numbers (e.g. "50 threads → exactly 10 × 201; 0 deadlocks over 5 runs").
If an earlier design was wrong, say what the test showed and how it was fixed.

## Not built
Deliberate scope cuts and the production path.
```

Rules: tables over prose; concrete numbers over adjectives; link the ADR from the README section it explains.
