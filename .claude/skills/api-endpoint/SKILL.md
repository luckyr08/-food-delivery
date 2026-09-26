---
name: api-endpoint
description: Add or change REST endpoints following this project's conventions — role-prefixed URLs, DTO records, ownership checks returning 404, ErrorCode-based Problem Details, pagination limits, and the matching tests. Use for any new controller or endpoint.
---

# API endpoint conventions

## URL and security
- Role-prefixed: `/api/admin/**`, `/api/owner/**`, `/api/partner/**`; customer under `/api/orders/**`;
  public GETs under `/api/restaurants/**`, `/api/cities/**`.
- New prefix → add a URL rule in `SecurityConfig` AND `@PreAuthorize` on the controller (defence in depth).
- Current user: `@AuthenticationPrincipal AuthUser me` (id + role, no DB lookup).

## Layers
controller (validation, HTTP status) → service (`@Transactional`, rules, ownership) → repository.
Controllers never touch repositories or entities.

## Ownership (IDOR)
- One query that combines existence and ownership: `findByIdAndOwnerId`, `findWith...ByIdAndCustomerId`.
- Nested resources check the whole chain (restaurant owned AND item/order belongs to that restaurant).
- Someone else's resource → 404 (not 403) so existence isn't leaked.

## DTOs and validation
- Java records; compact constructor trims/normalizes (runs before Bean Validation).
- PATCH: null = unchanged; if null is meaningful, use a dedicated endpoint (see stock PUT).
- Money `BigDecimal` with `@DecimalMin` + `@Digits(integer = 8, fraction = 2)`; never accept prices from clients.

## Errors
- Throw `NotFoundException` / `ConflictException` / `BadRequestException` / `ForbiddenException` with an
  `ErrorCode` (add new codes to the enum). Never build error JSON in controllers.

## Lists
- `PageResponse<T>`; `page >= 0`, `size` 1–100 via `@Min/@Max`; fixed server-side sort (no client `sort`).
- `@EntityGraph` for to-one associations shown in the list; never fetch collections in paged queries.

## Tests (minimum)
Happy path, validation 400, wrong role 403, other user's resource 404, business-rule 409, and the README
endpoint table row.
