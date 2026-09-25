# 0002 — JWT authentication with role-based access

**Status:** accepted

## Decision
`POST /api/auth/login` verifies the BCrypt password once and returns an HMAC-SHA256 signed JWT
(`sub`=userId, `role`, `exp`). A custom `OncePerRequestFilter` validates the token on each request.
Self-registration always creates a CUSTOMER; admins create restaurant owners and delivery partners;
one admin is seeded by migration.

## Options considered
| Option | Pros | Cons |
|---|---|---|
| HTTP Basic | ~20 lines, trivial to test | Password on every request; DB + BCrypt per request; no expiry |
| **JWT (jjwt + custom filter)** | Stateless, horizontally scalable, expiring, industry standard | Cannot revoke before expiry; role in token can go stale |
| Spring oauth2-resource-server | Less custom code | More opaque; OAuth/SSO is explicitly out of scope |

## Consequences
- Short expiry (60 min). Revocation (refresh tokens / denylist) is documented, not built.
- Authorization = role check + ownership check in services (e.g. owner edits only their restaurant).

## Implementation notes (step 3)
- Login uses Spring's `DaoAuthenticationProvider`: unknown email and wrong password both become
  `BadCredentialsException` ("Invalid email or password"), and a dummy BCrypt check for unknown users
  keeps response times equal (prevents user enumeration by timing).
- Claims: `sub` (user id), `role`, `iat`, `exp` — no email/PII, since the payload is readable by anyone. HS256 because the same app issues and verifies;
  RS256 would be needed if other services verified tokens.
- The JWT filter never rejects a request. An invalid/expired token leaves it anonymous and records the
  reason; public endpoints still work and protected ones return 401 `TOKEN_EXPIRED` / `INVALID_TOKEN`.
- The filter is not a `@Component` (Spring Boot would register it a second time as a servlet filter).
- Filter-level 401/403 are delegated to MVC's `HandlerExceptionResolver`, so `GlobalExceptionHandler`
  formats every error — one format, one place.
- Emails are trimmed (in the request record's compact constructor, before validation) and lower-cased.
- Password length 8–72: BCrypt ignores bytes beyond 72.
- Not built (documented): refresh tokens, revocation/denylist, rate limiting / lockout.
