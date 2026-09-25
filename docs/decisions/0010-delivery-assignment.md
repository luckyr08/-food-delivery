# 0010 — Delivery partner assignment

**Status:** accepted

## Model
Claim / broadcast: AVAILABLE partners see unassigned orders in their city (ACCEPTED, PREPARING,
READY_FOR_PICKUP) and claim one; the first claim wins. Claiming during preparation lets the partner travel
to the restaurant meanwhile; PLACED orders aren't claimable (the restaurant may still reject them).

| Alternative | Why not (here) |
|---|---|
| System dispatch (nearest partner offered, accept/decline with timeout) | Needs geo data and timeout jobs; scale-up path |
| Hybrid (offer to N nearest, first accept wins) | Same contention problem plus geo |

## Contention: two resources, two compare-and-set guards (one transaction)
```sql
UPDATE delivery_partners SET status='BUSY', version=version+1 WHERE id=:p AND status='AVAILABLE';     -- 0 → 409 PARTNER_NOT_AVAILABLE
UPDATE orders SET delivery_partner_id=:p, version=version+1
 WHERE id=:o AND delivery_partner_id IS NULL AND status IN ('ACCEPTED','PREPARING','READY_FOR_PICKUP'); -- 0 → 409 ORDER_ALREADY_ASSIGNED
```
- **CAS instead of `@Version`:** hot contention on one row (20 partners, one order). Optimistic locking would
  let all 20 read and compute, and 19 fail at commit; CAS decides in one statement.
  Rule of thumb: rare conflicts → optimistic lock (order lifecycle); hot single-row contention → CAS.
- **The claim bumps `orders.version`:** Hibernate writes all columns on an entity update, so an owner status
  change loaded before the claim would otherwise write `delivery_partner_id` back to NULL. With the bump it
  fails its version check (409, retry) instead of erasing the assignment.

## Lock order — corrected by a test
The design said "orders row, then partner row". `PartnerClaimConcurrencyTest` (one partner claiming five
orders at once) deadlocked: writing `orders.delivery_partner_id` makes the FK check take a **shared** lock on
the partner row, and `markBusy` then needs an **exclusive** lock on it — five transactions holding S and
waiting for X. Same S→X upgrade as order placement (ADR 0008).
**Fix:** `markBusy` (X on the partner) first, then the order claim; the FK check then hits our own lock.

Release paths (delivered, cancelled) lock order → partner. No cycle with claims: a claim proceeds past the
partner row only if the partner was AVAILABLE, while releases only touch BUSY partners (the assigned one).
Verified by repeated runs with 0 deadlocks.

## Partner status
`OFFLINE ⇄ AVAILABLE → (claim) BUSY → (delivered / order cancelled) AVAILABLE`.
Partners toggle AVAILABLE/OFFLINE (entity update, `@Version`); BUSY only by the system; no going OFFLINE
while BUSY. One active order per partner.

## Side effects (in the single transition path, ADR 0009)
- DELIVERED → COD payment PENDING → SUCCESS; partner → AVAILABLE.
- CANCELLED with a partner → partner → AVAILABLE; `delivery_partner_id` is kept as a record.
- OUT_FOR_DELIVERY only from READY_FOR_PICKUP and only by the assigned partner (others get 404).

## Not built
Partner un-claim (give an order back), batching several orders per partner, geo dispatch / ETA.
