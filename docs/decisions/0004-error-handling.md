# 0004 — Error handling and validation

**Status:** accepted

## Decision
All errors use RFC 9457 Problem Details (`application/problem+json`) plus two extensions:
`code` (stable `ErrorCode` for clients to branch on) and `timestamp`; validation errors add `errors[]`.
One `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` produces them.

## Status mapping
| Status | Meaning | Examples |
|---|---|---|
| 400 | Request itself is wrong; retrying unchanged always fails | Bean Validation, malformed JSON, wrong param type |
| 403 | Authenticated but not allowed | Wrong role for an action |
| 404 | Not found — **also when it exists but belongs to someone else** (don't leak existence) | Another customer's order |
| 409 | Valid request, current data state forbids it | Out of stock, invalid status transition, partner already assigned, `@Version` conflict, DB unique race |
| 500 | Our bug; generic message, stack trace only in logs | — |

## Options considered
| Option | Pros | Cons |
|---|---|---|
| Custom JSON shape | Full control | Non-standard; Spring's own errors would look different |
| **Problem Details** | HTTP standard; Spring emits it natively, so framework and business errors match | Less familiar to some clients |
| 422 for business rules | Semantically precise | Debated; 400/409 split is simpler to reason about |

## Consequences
- Business exceptions are unchecked (`ApiException extends RuntimeException`) because `@Transactional`
  rolls back only on unchecked exceptions by default.
- DB constraint messages (`DataIntegrityViolationException`) are logged, never returned.
- 4xx logged at WARN without stack; 5xx at ERROR with stack.
- The advice only sees controller exceptions. 401/403 raised inside the security filter chain
  need an `AuthenticationEntryPoint` / `AccessDeniedHandler` writing the same shape (step 3).
- Validation happens in three layers: request DTO annotations → service business rules → DB constraints.
