# Low-Level Design — Food Delivery Order Management

Companion to the [HLD](HLD.md). Everything here matches the code (`src/main/java/com/fooddelivery`) and the
Flyway migrations (`src/main/resources/db/migration`, V1–V5).

## 1. Data model (ER)

```mermaid
erDiagram
    users ||--o{ restaurants : owns
    users ||--o| delivery_partners : "profile of"
    users ||--o{ orders : places
    users ||--o{ reviews : writes
    users ||--o{ notifications : receives
    cities ||--o{ restaurants : contains
    cities ||--o{ delivery_partners : "operates in"
    restaurants ||--o{ menu_items : offers
    restaurants ||--o{ orders : receives
    restaurants ||--o{ reviews : "rated in"
    delivery_partners ||--o{ orders : delivers
    orders ||--|{ order_items : contains
    menu_items ||--o{ order_items : "snapshotted in"
    orders ||--|{ order_status_history : "tracked by"
    orders ||--|| payments : "paid by"
    orders ||--o| reviews : "reviewed in"
    orders ||--o{ notifications : about

    users {
        bigint id PK
        varchar email UK
        varchar password_hash "BCrypt"
        varchar role "ADMIN|RESTAURANT_OWNER|CUSTOMER|DELIVERY_PARTNER"
        boolean active
    }
    cities {
        bigint id PK
        varchar name UK "case-insensitive (collation)"
        boolean active
    }
    restaurants {
        bigint id PK
        bigint owner_id FK
        bigint city_id FK
        boolean is_open "owner"
        boolean active "admin soft delete"
        int rating_sum
        int rating_count
        bigint search_version "bumped with every indexed change"
        bigint version "@Version"
    }
    menu_items {
        bigint id PK
        bigint restaurant_id FK
        decimal price "DECIMAL(10,2)"
        boolean available "owner toggle"
        int stock "NULL = unlimited, CHECK >= 0"
        boolean active
        bigint version "@Version"
    }
    delivery_partners {
        bigint id PK
        bigint user_id FK "UK"
        bigint city_id FK
        varchar status "OFFLINE|AVAILABLE|BUSY"
        int rating_sum
        int rating_count
        bigint version
    }
    orders {
        bigint id PK
        bigint customer_id FK
        bigint restaurant_id FK
        bigint delivery_partner_id FK "NULL until claimed"
        varchar status
        decimal total_amount
        varchar idempotency_key "UK with customer_id"
        char idempotency_request_hash "SHA-256"
        bigint version "@Version"
    }
    order_items {
        bigint id PK
        bigint order_id FK
        bigint menu_item_id FK
        varchar item_name "snapshot"
        decimal unit_price "snapshot"
        int quantity
    }
    order_status_history {
        bigint id PK
        bigint order_id FK
        varchar from_status
        varchar to_status
        bigint changed_by FK
        datetime changed_at
    }
    payments {
        bigint id PK
        bigint order_id FK "UK"
        varchar method
        varchar status "PENDING|SUCCESS|FAILED|REFUNDED|VOIDED"
        varchar provider_ref
    }
    reviews {
        bigint id PK
        bigint order_id FK "UK: one per order"
        tinyint restaurant_rating "1-5"
        tinyint partner_rating "1-5, optional"
    }
    notifications {
        bigint id PK
        bigint recipient_user_id FK
        bigint order_id FK
        varchar type
        boolean is_read
    }
    outbox_event {
        bigint id PK
        varchar aggregate_type
        bigint aggregate_id
        datetime next_attempt_at
        int attempts
        datetime processed_at
    }
```

**Indexes (each backs a query):** `restaurants(city_id, active)` browse · `menu_items(restaurant_id, active)` menu ·
`orders(customer_id, created_at)` my orders · `orders(restaurant_id, status)` kitchen queue ·
`orders(status, delivery_partner_id)` claimable orders · `reviews(restaurant_id, created_at)` public reviews ·
`notifications(recipient_user_id, created_at)` feed · `outbox_event(processed_at, next_attempt_at, id)` relay claim.

## 2. Module class diagrams

### 2.1 Ordering

```mermaid
classDiagram
    class OrderController {
        +place(me, idempotencyKey, request) ResponseEntity
        +cancel(me, id, request) OrderResponse
        +timeline(me, id) List
    }
    class OrderRateLimiter {
        +check(customerId) void
    }
    class OrderAdmissionControl {
        -Semaphore permits
        +admit(placement) Object
    }
    class OrderService {
        +placeOrder(customerId, request, key) PlacementResult
        +getForCustomer(orderId, customerId) OrderResponse
    }
    class OrderPlacementTx {
        +place(customerId, request, key, hash) PlacementResult
        +replay(customerId, key, hash) PlacementResult
    }
    class OrderLifecycleService {
        +ownerUpdate(restaurantId, orderId, ownerId, to, reason) OrderResponse
        +customerCancel(orderId, customerId, reason) OrderResponse
        +partnerUpdate(orderId, partnerUserId, to) OrderResponse
        +adminCancel(orderId, adminId, reason) OrderResponse
        ~transition(order, to, actorId, role, reason) OrderResponse
    }
    class OrderStateMachine {
        <<utility>>
        +check(from, to, role)$ void
        +restocksOnCancel(from)$ boolean
    }
    class PaymentService {
        +charge(order, method) Payment
        +reverse(order) void
        +settleOnDelivery(order) void
    }
    class PaymentGateway {
        <<interface>>
        +charge(request) ChargeResult
        +refund(providerRef, amount) void
    }
    class MockPaymentGateway
    class MenuItemRepository {
        +deductStock(id, quantity) int
        +restock(id, quantity) int
    }

    OrderController --> OrderRateLimiter
    OrderController --> OrderAdmissionControl
    OrderController --> OrderService
    OrderController --> OrderLifecycleService
    OrderService --> OrderPlacementTx
    OrderPlacementTx --> MenuItemRepository
    OrderPlacementTx --> PaymentService
    OrderLifecycleService --> OrderStateMachine
    OrderLifecycleService --> PaymentService
    OrderLifecycleService --> MenuItemRepository
    PaymentService --> PaymentGateway
    PaymentGateway <|.. MockPaymentGateway
```

`OrderService.placeOrder` is `@Retryable` (outside the transaction); `OrderPlacementTx.place` is
`@Transactional`; `PaymentService.charge/reverse` are `Propagation.MANDATORY` (see §5).

### 2.2 Delivery

```mermaid
classDiagram
    class PartnerController {
        +claim(me, orderId) OrderResponse
        +updateStatus(me, orderId, request) OrderResponse
    }
    class DeliveryAssignmentService {
        +setAvailability(userId, status) DeliveryPartnerResponse
        +availableOrders(userId, page, size) PageResponse
        +claim(orderId, userId) OrderResponse
        +currentOrder(userId) Optional
    }
    class DeliveryPartnerRepository {
        +markBusy(id) int
        +markAvailable(id) int
        +addRating(id, rating) int
    }
    class OrderRepository {
        +claim(orderId, partnerId) int
        +findClaimableInCity(cityId, pageable) Page
    }
    class OrderLifecycleService {
        +partnerUpdate(orderId, partnerUserId, to) OrderResponse
    }
    PartnerController --> DeliveryAssignmentService
    PartnerController --> OrderLifecycleService
    DeliveryAssignmentService --> DeliveryPartnerRepository
    DeliveryAssignmentService --> OrderRepository
```

### 2.3 Search and outbox

```mermaid
classDiagram
    class OutboxWriter {
        +restaurantChanged(restaurantId) void
        +cityChanged(cityId) void
    }
    class OutboxRepository {
        +append(type, id, eventType) void
        +claimBatch(limit) List
        +markProcessed(ids) void
        +markFailed(ids, error) void
    }
    class OutboxRelay {
        +processBatch() int
        +drain() int
    }
    class OutboxHandler {
        <<interface>>
        +aggregateType() String
        +handle(aggregateIds) void
    }
    class SearchProjector {
        +handle(restaurantIds) void
        +reindexAll() int
    }
    class RestaurantDocumentLoader {
        ~load(ids) Loaded
        ~loadAll() Loaded
    }
    class SearchIndex {
        <<interface>>
        +upsertAll(documents) void
        +delete(id, version) void
        +search(query) SearchHits
        +suggest(cityId, prefix, limit) List
        +rebuild(loader) int
    }
    class InMemorySearchIndex {
        simulated Elasticsearch
    }
    class SearchService {
        +search(query) SearchResponse
    }
    class RestaurantBrowseService {
        MySQL fallback
    }

    OutboxWriter --> OutboxRepository
    OutboxRelay --> OutboxRepository
    OutboxRelay --> OutboxHandler
    OutboxHandler <|.. SearchProjector
    SearchProjector --> RestaurantDocumentLoader
    SearchProjector --> SearchIndex
    SearchIndex <|.. InMemorySearchIndex
    SearchService --> SearchIndex
    SearchService --> RestaurantBrowseService
```

`OutboxWriter` methods are `Propagation.MANDATORY`; `claimBatch` uses `FOR UPDATE SKIP LOCKED`;
the relay runs each batch at READ COMMITTED.

### 2.4 Security and error pipeline

```mermaid
flowchart LR
    REQ[HTTP request] --> F[JwtAuthenticationFilter<br/>valid → AuthUser in context<br/>invalid → reason attribute]
    F --> AZ{AuthorizationFilter<br/>URL rules}
    AZ -->|anonymous| EP[ProblemDetailsSecurityHandler<br/>401 + WWW-Authenticate]
    AZ -->|wrong role| DH[ProblemDetailsSecurityHandler<br/>403]
    AZ -->|ok| CTRL[Controller<br/>@PreAuthorize, @Valid]
    CTRL --> SVC[Service<br/>ownership query → 404]
    EP --> HER[HandlerExceptionResolver]
    DH --> HER
    CTRL -. exceptions .-> GEH[GlobalExceptionHandler<br/>RFC 9457 + code]
    HER --> GEH
```

## 3. State machines

### Delivery partner

```mermaid
stateDiagram-v2
    [*] --> OFFLINE: created by admin
    OFFLINE --> AVAILABLE: partner
    AVAILABLE --> OFFLINE: partner
    AVAILABLE --> BUSY: claim (CAS)
    BUSY --> AVAILABLE: order DELIVERED / CANCELLED
    note right of BUSY: cannot go OFFLINE while BUSY (409)
```

### Payment

```mermaid
stateDiagram-v2
    [*] --> INITIATED: online, order PAYMENT_PENDING
    [*] --> PENDING: cash on delivery
    INITIATED --> SUCCESS: gateway approved (after commit)
    INITIATED --> FAILED: declined / expired
    SUCCESS --> REFUND_PENDING: order rejected / cancelled
    REFUND_PENDING --> REFUNDED: outbox relay refunds (retried)
    PENDING --> SUCCESS: delivered (cash collected)
    PENDING --> VOIDED: order cancelled
    note left of INITIATED: gateway is never called<br/>inside a DB transaction
```

The order state machine is in the [HLD §6](HLD.md#6-order-lifecycle).

## 4. Concurrency control catalogue

| Operation | Contended resource | Technique | Lock order / notes |
|---|---|---|---|
| Place order | `menu_items.stock` | Conditional `UPDATE ... WHERE stock >= :q` | orders row inserted → stock X-locks (ascending item id) → order_items (FK) |
| Idempotent placement | `(customer_id, idempotency_key)` | UNIQUE + replay on duplicate | order row inserted before stock so duplicates never touch stock |
| Status transition | `orders` row | `@Version`, flushed before side effects | loser rolls back incl. restock/refund |
| Restock | `menu_items.stock` | `stock = stock + q` | ascending item id |
| Partner claim | `delivery_partners`, `orders` | Two compare-and-set UPDATEs | partner row → order row (FK S→X rule) |
| Partner release | `delivery_partners` | CAS `BUSY → AVAILABLE` | order row → partner row; no cycle (claims only continue for AVAILABLE partners) |
| Owner stock set | `menu_items` | `@Version` (sales bump version) | 409 on concurrent sale |
| Review | `restaurants/partners` aggregates, `reviews.order_id` | Atomic increments + UNIQUE | increment before inserting the review (FK S→X rule) |
| Search sync | `restaurants.search_version`, `outbox_event` | Bump in business tx; relay `FOR UPDATE SKIP LOCKED` at READ COMMITTED; external versioning in the index | bump before modifying menu items |
| Order spike | DB connections | Semaphore bulkhead + token bucket | 503 / 429 with `Retry-After` |

**Rule learned three times:** X-lock a parent row before writing a child that references it; the FK check
takes a shared lock and concurrent S→X upgrades deadlock (ADR 0008, 0010, 0012).

## 5. Transactions and isolation

| Unit | Boundary | Isolation |
|---|---|---|
| Request services | `@Transactional` on service methods; `open-in-view` off | REPEATABLE READ (InnoDB default); UPDATE = locking read of latest row |
| Placement retry | `OrderService` (no tx) wraps `OrderPlacementTx` | retry must be outside the rolled-back tx |
| Payment / outbox writes | `Propagation.MANDATORY` | must join the business transaction; never call the gateway |
| Payment saga | `OrderCheckoutService` (no tx): Tx1 reserve → gateway → Tx2 confirm/compensate | gateway call holds no locks or connections |
| Notifications | after commit, own tx per recipient on `notify-*` | — |
| Outbox relay | `TransactionTemplate` per batch | READ COMMITTED (no gap-lock deadlocks) |

## 6. API conventions

- Role prefixes: `/api/admin/**`, `/api/owner/**`, `/api/partner/**`; customer `/api/orders/**`;
  public `GET /api/cities|restaurants|search/**`. Full table in the [README](../../README.md#api-overview).
- Requests are records validated with Bean Validation (compact constructors normalise input first).
- Lists return `PageResponse {content, page, size, totalElements, totalPages}`; `size` 1–100; server-side sort.
- Errors are Problem Details with a stable `code`, e.g.
  `{"status":409,"code":"INSUFFICIENT_STOCK","detail":"'Chicken Biryani' is out of stock ...","instance":"/api/orders"}`.

## 7. Configuration

| Property | Default | Purpose |
|---|---|---|
| `app.jwt.expiration-minutes` | 60 | token lifetime |
| `app.order.delivery-fee` / `free-delivery-from` | 40.00 / 500.00 | pricing |
| `app.order.admission.max-concurrent` / `acquire-timeout-ms` | 16 / 200 | bulkhead |
| `app.order.rate-limit.capacity` / `refill-per-minute` | 5 / 5 | per-customer limit |
| `app.review.window-days` | 7 | review window |
| `app.outbox.relay.enabled` / `poll-interval-ms` / `batch-size` | true / 1000 / 100 | search sync |
| `spring.datasource.hikari.connection-timeout` | 2000 | fail fast |
| `innodb_lock_wait_timeout` (session) | 5 s | fail fast on lock waits |
