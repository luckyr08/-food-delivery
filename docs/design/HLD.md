# High-Level Design — Food Delivery Order Management

Scope: a single Spring Boot service (modular monolith) backed by MySQL, with an asynchronous notification
pipeline and a search index kept in sync through a transactional outbox. Distributed infrastructure
(Kafka, Redis, real Elasticsearch) was out of scope; where the design depends on it, a port/adapter or a
documented production path is used (see ADRs in [`docs/decisions`](../decisions)).

## 1. Requirements

**Functional (from the brief):** multi-city restaurants and menus; customers browse, order, track, rate;
restaurant owners manage menus and accept/reject orders; delivery partners claim orders and update
delivery status; admins manage cities, restaurants and partners.

**Hard problems called out by the brief → design answer**

| Requirement | Design answer | ADR |
|---|---|---|
| No overselling under concurrent orders | Single conditional `UPDATE ... WHERE stock >= :q` per item | [0008](../decisions/0008-order-placement.md) |
| Stock + order + payment atomic | Saga: reserve in one short transaction, charge after commit, confirm or compensate; reconciler for unknown outcomes | [0015](../decisions/0015-payment-saga.md) |
| Partners contending for one order | Two compare-and-set updates (partner AVAILABLE→BUSY, order unassigned→partner) | [0010](../decisions/0010-delivery-assignment.md) |
| Status fan-out without blocking | `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` pool | [0011](../decisions/0011-async-notifications.md) |
| Ratings after delivery | Atomic sum/count increments, one review per order | [0012](../decisions/0012-ratings-and-reviews.md) |

**Non-functional:** correctness under concurrency (proven by multi-threaded tests on real MySQL), stateless
horizontal scaling, graceful degradation (search fallback, 503/429 with `Retry-After`), least privilege,
auditability (status history, ADRs).

## 2. System context

```mermaid
flowchart LR
    C[Customer app] -->|HTTPS + JWT| API
    O[Restaurant owner app] -->|HTTPS + JWT| API
    P[Delivery partner app] -->|HTTPS + JWT| API
    A[Admin console] -->|HTTPS + JWT| API

    subgraph API[Food Delivery API - Spring Boot, stateless]
        direction TB
        WEB[REST controllers<br/>JWT filter, RBAC, validation]
        CORE[Domain services<br/>orders, menu, delivery, reviews]
        ASYNC[notify-* thread pool]
        RELAY[Outbox relay<br/>@Scheduled 1 s]
    end

    API -->|JDBC, source of truth| DB[(MySQL 8<br/>InnoDB)]
    RELAY -->|versioned upserts| IDX[(Search index<br/>Elasticsearch port)]
    WEB -->|search queries| IDX
    CORE -->|charge / refund| PG[Payment gateway<br/>mock]
    PG -->|signed webhook| WEB
    ASYNC -->|push| PUSH[Push / SMS provider<br/>logging stub]
```

## 3. Architecture — modular monolith, package by feature

```mermaid
flowchart TB
    subgraph edge[Edge]
        SEC[security<br/>JWT filter, SecurityConfig]
        ERR[common.error<br/>Problem Details]
    end
    AUTH[auth] --> USER[user]
    ADMIN_CITY[city] --> OUTBOX
    REST[restaurant] --> CITY_S[city]
    REST --> OUTBOX[outbox]
    MENU[menu] --> REST
    MENU --> OUTBOX
    ORDER[order] --> MENU
    ORDER --> REST
    ORDER --> PAY[payment]
    ORDER --> DEL[delivery]
    DEL --> ORDER
    RATING[rating] --> ORDER
    RATING --> OUTBOX
    NOTIF[notification] -. listens to OrderEvent .-> ORDER
    SEARCH[search] -. OutboxHandler .-> OUTBOX
    SEARCH --> REST
```

- Each feature package has controller → service → repository; controllers exchange DTO records only.
- Cross-feature side effects are either direct service calls inside the same transaction (restock,
  refund, partner release) or events (notifications after commit, search via outbox).
- Known mild smell: `order ↔ delivery` depend on each other (claim vs release); an in-transaction domain
  event would decouple them.

## 4. Read vs write paths (CQRS-lite)

```mermaid
flowchart LR
    subgraph writes[Writes - strongly consistent]
        W1[Place order] --> TX[(MySQL transaction)]
        W2[Menu / restaurant / city edits] --> TX
        W3[Status changes, claims, reviews] --> TX
    end
    TX -->|same transaction| OB[(outbox_event)]
    TX -->|after commit| EV[OrderEvent]
    OB --> RL[Relay] --> IDX[(Search index)]
    EV --> NP[notify-* pool] --> NT[(notifications)]

    subgraph reads[Reads]
        R1[Search: typos, dishes] --> IDX
        R2[Browse / menu / orders / tracking] --> DB[(MySQL)]
    end
```

- MySQL is the source of truth; the search index is an eventually consistent projection (~1 s).
- Stock is never read from the index: the real-time check happens in the order transaction.

## 5. Key flows

### 5.1 Order placement

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant API as OrderController
    participant RL as OrderRateLimiter
    participant BH as OrderAdmissionControl
    participant OS as OrderService (retry, idempotency race)
    participant TX as OrderPlacementTx (Tx1)
    participant DB as MySQL
    participant PG as Payment gateway
    participant EV as Event listener (after commit)

    C->>API: POST /api/orders (+Idempotency-Key)
    API->>RL: check(customer) — 429 if over limit
    API->>BH: admit() — 503 + Retry-After if no slot in 200 ms
    BH->>OS: placeOrder()
    OS->>TX: place()
    TX->>DB: key already used? → replay (200)
    TX->>DB: restaurant open? items valid? price from DB
    TX->>DB: INSERT orders (claims idempotency key)
    loop items in ascending id
        TX->>DB: UPDATE menu_items SET stock=stock-q WHERE stock>=q
        DB-->>TX: 0 rows → 409 INSUFFICIENT_STOCK (rollback all)
    end
    TX->>DB: INSERT order_items (snapshot), history, payment INITIATED
    TX->>DB: COMMIT (status PAYMENT_PENDING, locks released)
    Note over OS: deadlock/lock timeout → @Retryable (outside tx) → else 503
    OS->>PG: charge(ref = order id) — outside any transaction
    alt approved
        OS->>DB: Tx2: payment SUCCESS, order PLACED
        DB-->>EV: OrderEvent AFTER_COMMIT (restaurant notified)
        OS-->>API: 201 Created
    else declined
        OS->>DB: Tx2: payment FAILED, order CANCELLED, restock
        OS-->>API: 402
    else timeout / unknown
        OS-->>API: 202 Accepted (PAYMENT_PENDING)
        Note over PG,DB: PaymentReconciler asks the gateway later, completes or cancels
    end
```

### 5.2 Delivery partner claim (contention)

```mermaid
sequenceDiagram
    autonumber
    actor P1 as Partner 1
    actor P2 as Partner 2
    participant S as DeliveryAssignmentService
    participant DB as MySQL

    par same instant
        P1->>S: claim(order 42)
    and
        P2->>S: claim(order 42)
    end
    S->>DB: P1: UPDATE delivery_partners SET BUSY WHERE id=P1 AND status='AVAILABLE'
    S->>DB: P2: UPDATE delivery_partners SET BUSY WHERE id=P2 AND status='AVAILABLE'
    S->>DB: P1: UPDATE orders SET partner=P1 WHERE id=42 AND partner IS NULL → 1 row
    S->>DB: P2: same → 0 rows (row lock waited for P1's commit)
    S-->>P1: 200 OK
    S-->>P2: 409 ORDER_ALREADY_ASSIGNED (P2's BUSY rolled back)
    Note over S,DB: partner row locked before orders.delivery_partner_id is written<br/>(FK S-lock → X upgrade would deadlock)
```

### 5.3 Search index synchronisation (no lost updates)

```mermaid
sequenceDiagram
    autonumber
    participant SVC as Service (e.g. menu edit)
    participant DB as MySQL
    participant R as OutboxRelay (READ COMMITTED)
    participant PJ as SearchProjector
    participant IX as SearchIndex

    SVC->>DB: UPDATE restaurants SET search_version=search_version+1
    SVC->>DB: INSERT outbox_event(RESTAURANT, id)
    SVC->>DB: UPDATE menu_items ... ; COMMIT (all or nothing)
    loop every 1 s
        R->>DB: SELECT due rows FOR UPDATE SKIP LOCKED
        R->>PJ: handle(distinct restaurant ids)  (coalescing)
        PJ->>DB: read CURRENT state + search_version (one snapshot)
        PJ->>IX: upsert(doc, version=search_version) / versioned delete
        alt index unavailable
            R->>DB: attempts+1, next_attempt_at += 2^n s, last_error
        else ok
            R->>DB: processed_at = now
        end
    end
```

### 5.4 Notification fan-out

```mermaid
sequenceDiagram
    participant TX as Business transaction
    participant PUB as ApplicationEventPublisher
    participant L as OrderNotificationListener<br/>@Async + AFTER_COMMIT
    participant NS as NotificationService (own tx per recipient)
    participant PS as NotificationSender

    TX->>PUB: publishEvent(OrderEvent ids only)
    TX->>TX: COMMIT
    PUB-->>L: dispatched on notify-* thread (HTTP already answered)
    loop customer, owner, partner (minus actor)
        L->>NS: record(in-app notification)
        L->>PS: send(push)
        Note right of L: try/catch per recipient
    end
```

## 6. Order lifecycle

```mermaid
stateDiagram-v2
    [*] --> PAYMENT_PENDING: customer places (online)
    [*] --> PLACED: customer places (cash on delivery)
    PAYMENT_PENDING --> PLACED: system (charge approved / reconciled)
    PAYMENT_PENDING --> CANCELLED: system (declined / expired)
    PLACED --> ACCEPTED: owner
    PLACED --> REJECTED: owner (reason)
    PLACED --> CANCELLED: customer / admin
    ACCEPTED --> PREPARING: owner
    ACCEPTED --> CANCELLED: customer / admin
    PREPARING --> READY_FOR_PICKUP: owner
    PREPARING --> CANCELLED: admin
    READY_FOR_PICKUP --> OUT_FOR_DELIVERY: assigned partner
    READY_FOR_PICKUP --> CANCELLED: admin
    OUT_FOR_DELIVERY --> DELIVERED: assigned partner
    OUT_FOR_DELIVERY --> CANCELLED: admin
    DELIVERED --> [*]
    REJECTED --> [*]
    CANCELLED --> [*]
    note right of ACCEPTED: partners may claim from ACCEPTED,<br/>PREPARING or READY_FOR_PICKUP
```

Side effects: REJECTED/CANCELLED → refund (or void COD), restock only if not yet cooked, release partner;
DELIVERED → COD settled, partner released. Every change → history row + OrderEvent.

## 7. Deployment

```mermaid
flowchart LR
    subgraph now[Today]
        A1[App instance] --> M1[(MySQL)]
        A1 --- I1[(In-memory index)]
    end
    subgraph target[Production target]
        LB[Load balancer] --> B1[App 1] & B2[App 2] & B3[App N]
        B1 & B2 & B3 --> PX[ProxySQL] --> PRI[(MySQL primary<br/>sharded by city)]
        PRI --> REP[(Read replicas)]
        B1 & B2 & B3 --> ES[(Elasticsearch cluster)]
        B1 & B2 & B3 --> RD[(Redis: stock gate,<br/>rate limits, cache)]
        B1 & B2 & B3 --> K[[Kafka: order intake,<br/>events]]
        PRI -. CDC Debezium .-> K --> ES
    end
```

The app is stateless (JWT, no sessions) and all concurrency control lives in the database, so adding
instances needs no code change; the limits are DB connections and hot rows (see ADR 0014).

## 8. Cross-cutting concerns

| Concern | Approach |
|---|---|
| Security | Stateless JWT (HS256, 60 min), RBAC via URL rules + `@PreAuthorize`, ownership-scoped queries (404 for others' data), BCrypt, timing-safe login, least-privilege DB user |
| Errors | RFC 9457 Problem Details with stable `code`; 400/401/403/404/409/402/429/503/500 |
| Consistency | Conditional UPDATEs for hot rows, `@Version` for rare conflicts, unique constraints for exactly-once creation, lock-order rules |
| Resilience | Bulkhead + rate limit, fail-fast timeouts, retry outside transactions, search fallback to MySQL, outbox retries with backoff |
| Observability | Structured logs, status history, `/api/admin/search/status`; metrics/tracing out of scope |
| Testing | Unit, web slice, integration on real MySQL, async (Awaitility), concurrency (real server + latch) — 190 tests |
