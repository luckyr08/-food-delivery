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
