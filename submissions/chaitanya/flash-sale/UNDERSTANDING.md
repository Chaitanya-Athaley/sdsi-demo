# Understanding the Flash Sale Inventory Reservation System

A deep-dive walkthrough of the architecture, design decisions, concurrency model, and code flow.

---

## Table of Contents

1. [The Problem](#1-the-problem)
2. [Solution at a Glance](#2-solution-at-a-glance)
3. [Architecture Layers](#3-architecture-layers)
4. [The Concurrency Model (Deep Dive)](#4-the-concurrency-model-deep-dive)
5. [Stock Accounting Invariant](#5-stock-accounting-invariant)
6. [Reservation Lifecycle State Machine](#6-reservation-lifecycle-state-machine)
7. [Purchase Limit Enforcement](#7-purchase-limit-enforcement)
8. [Request Flow Walkthroughs](#8-request-flow-walkthroughs)
9. [Testing Strategy](#9-testing-strategy)
10. [Design Decisions & Trade-offs](#10-design-decisions--trade-offs)
11. [Production Hardening Gap Analysis](#11-production-hardening-gap-analysis)
12. [Quick Reference](#12-quick-reference)

---

## 1. The Problem

Flash sales create a burst of concurrent purchase requests targeting limited inventory. Without proper controls, two users can simultaneously reserve or buy the last unit of a product — **overselling**. This is the classic inventory race condition:

```
Thread A reads available=1    Thread B reads available=1
       |                              |
   deducts to 0                  deducts to 0
       |                              |
   writes available=0            writes available=0
       
Result: 2 units sold, only 1 existed → oversell
```

The system must also:
- Hold stock temporarily (reservations with a TTL)
- Enforce per-user purchase limits
- Handle concurrent confirm, cancel, and expire operations without double-counting stock

---

## 2. Solution at a Glance

| Concern | Approach |
|---------|----------|
| **Oversell prevention** | Pessimistic write locking — each SKU row is locked (`PESSIMISTIC_WRITE`) before any stock mutation |
| **Concurrent lifecycle ops** | Consistent lock ordering: product row locked before reservation row |
| **Stock integrity** | Invariant `initialStock = available + reserved + sold` enforced in domain entity |
| **Time handling** | Injectable `Clock` bean — tests freeze time, production uses system clock |
| **Error responses** | RFC 9457 Problem Details with typed error codes |

The core insight: **by serialising all stock mutations for a SKU at the database level, we eliminate race conditions without retry logic or distributed coordination.**

---

## 3. Architecture Layers

```
┌────────────────────────────────────────────────────────────┐
│                   API Layer (REST)                          │
│  InventoryController          ReservationController         │
│  - DTO request/response mapping                             │
│  - @Valid Jakarta Bean Validation                           │
│  - Routes to service layer                                  │
├────────────────────────────────────────────────────────────┤
│               Application / Service Layer                   │
│  InventoryService             ReservationService            │
│  - @Transactional boundaries                                │
│  - Orchestrates locks, validation, domain mutations         │
│  - Handles purchase-limit enforcement                       │
├────────────────────────────────────────────────────────────┤
│                   Domain Layer                              │
│  Product (entity)              Reservation (entity)         │
│  - Stock mutation methods: reserve(), confirm(),            │
│    releaseReservation()                                     │
│  - State machine: RESERVED → CONFIRMED/CANCELLED/EXPIRED    │
│  - Invariant checks, validation                             │
│  ReservationStatus enum                                     │
│  BusinessException hierarchy (6 typed exceptions)           │
├────────────────────────────────────────────────────────────┤
│                 Persistence Layer                           │
│  ProductRepository            ReservationRepository         │
│  - findBySkuForUpdate() → PESSIMISTIC_WRITE lock            │
│  - findByIdForUpdate() → PESSIMISTIC_WRITE lock             │
│  - Aggregate queries for purchase limits                    │
│  - Flyway migration V1__create_tables.sql                   │
├────────────────────────────────────────────────────────────┤
│                 Infrastructure                              │
│  ExpirationScheduler (@Scheduled, 5s interval)              │
│  InventoryLoader (idempotent, ApplicationReadyEvent)       │
│  SecurityConfig (dev-focused, stateless)                    │
│  Clock bean (injectable, test-friendly)                     │
│  GlobalExceptionHandler (@RestControllerAdvice)            │
└────────────────────────────────────────────────────────────┘
```

### Layer responsibilities in detail

**API Layer** (`controller/`)
- Thin — validates input shape (`@Valid`), delegates to service, maps entities → DTOs.
- No business logic. No transaction management.

**Service Layer** (`service/`)
- Owns `@Transactional` boundaries — every stock-mutating operation is one transaction.
- Gets the product lock, validates, mutates domain entities, returns.
- The transaction is the unit of consistency — if anything fails, everything rolls back.

**Domain Layer** (`domain/`)
- `Product`: knows how to `reserve()`, `confirm()`, `releaseReservation()`. Guards its own invariants.
- `Reservation`: knows its lifecycle. `confirm()` checks state and expiry. `cancel()` checks state. `expire()` is idempotent.
- Exceptions extend `BusinessException` — typed, with error codes, handled by `GlobalExceptionHandler`.

**Persistence Layer** (`repository/`)
- Spring Data JPA interfaces with custom `@Query` methods.
- The two critical methods are `findBySkuForUpdate()` and `findByIdForUpdate()` — both annotated with `@Lock(LockModeType.PESSIMISTIC_WRITE)`.

---

## 4. The Concurrency Model (Deep Dive)

### 4.1 Pessimistic Write Locking

Every stock-mutating operation follows this pattern:

```
1. BEGIN TRANSACTION
2. SELECT ... FROM products WHERE sku = ? FOR UPDATE   ← PESSIMISTIC_WRITE lock acquired
3. [All other threads for this SKU block here until step 6]
4. Validate stock, purchase limits
5. Mutate entities (reserve, confirm, release)
6. COMMIT (or ROLLBACK on failure)                     ← lock released
```

This is implemented in JPA via:

```java
// ProductRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.sku = :sku")
Optional<Product> findBySkuForUpdate(@Param("sku") String sku);
```

**Why not optimistic locking?** Optimistic locking would require retry logic. In a flash sale with thousands of concurrent requests hitting the same SKU, retry storms amplify database load exactly when it's most stressed. Pessimistic locking accepts the serialisation up front and avoids wasted work.

**Why not `UPDATE ... WHERE available >= ?`?** Atomic conditional SQL would bypass JPA's entity state. After the UPDATE, the in-memory Product entity would be stale — you'd need explicit `refresh()` calls. In a layered architecture with domain logic in entities, pessimistic locking integrates cleanly: lock, read, mutate entity in Java, flush on commit.

### 4.2 Lock Ordering

Operations that touch both product and reservation (confirm, cancel, expire) lock in a **fixed order**:

```
1. Lock product row   ← findBySkuForUpdate(sku)
2. Lock reservation   ← findByIdForUpdate(reservationId)
```

Never:
```
1. Lock reservation   ← would deadlock with another thread doing the opposite
2. Lock product row
```

**Why this prevents deadlocks:** Two threads both trying to confirm reservations for the same SKU:

```
Thread A: lock product(SKU-X) → acquired, blocks on reservation-1
Thread B: lock product(SKU-X) → blocked until A commits
```

Thread B blocks at step 1, not step 2 — no deadlock. Both threads acquire locks in the same order.

### 4.3 Transaction Boundaries

Each service method is its own `@Transactional`:

| Method | Locks Acquired | What It Does |
|--------|---------------|--------------|
| `createReservation()` | Product (WRITE) | Validates stock + limits, calls `product.reserve()`, saves reservation |
| `confirmReservation()` | Product (WRITE) → Reservation (WRITE) | Validates state + expiry, calls `reservation.confirm()` + `product.confirm()` |
| `cancelReservation()` | Product (WRITE) → Reservation (WRITE) | Validates state, calls `reservation.cancel()` + `product.releaseReservation()` |
| `expireReservation()` | Reservation (WRITE) → Product (WRITE) | Idempotent, calls `reservation.expire()` + `product.releaseReservation()` |

### 4.4 Expiration Scheduler

Runs every 5 seconds (configurable). Queries:

```sql
SELECT * FROM reservations
WHERE status = 'RESERVED' AND expires_at <= :now
LIMIT 100
```

Uses the `(expires_at, status)` index for efficient scans. Each reservation is expired in its **own transaction** (via `ReservationService.expireReservation()`) so one failure doesn't roll back the batch.

The scheduler query itself uses `PESSIMISTIC_WRITE` — this means the scheduler locks the overdue reservation rows before the service method runs. This prevents a race where the scheduler reads a row, then a confirm/cancel sneaks in before the expire.

---

## 5. Stock Accounting Invariant

```
initialStock = availableStock + reservedStock + soldStock
```

**Where it's enforced:**

1. **Domain entity** (`Product.java`): Every mutation method (`reserve`, `confirm`, `releaseReservation`) manipulates the counters such that the sum stays equal to `initialStock`.

   ```java
   // reserve():  available -= N, reserved += N   → sum unchanged
   // confirm():  reserved -= N, sold += N        → sum unchanged
   // releaseReservation(): reserved -= N, available += N  → sum unchanged
   ```

2. **Database CHECK constraints**: All four stock columns have `CHECK (column >= 0)`, preventing negative values at the DB level.

3. **Tests**: Every test (unit, integration, concurrency) asserts `product.isStockInvariantSatisfied()` after operations.

**Why this matters:** If stock ever goes missing (e.g., a bug in concurrent confirm/cancel), the invariant catches it. It's a canary — if `initialStock ≠ available + reserved + sold`, something is wrong.

---

## 6. Reservation Lifecycle State Machine

```
                         ┌──────────┐
                    ┌───▶│ RESERVED │◀───┐
                    │    └────┬──┬──┘    │
                    │         │  │       │
               confirm()  cancel() expire()
                    │         │  │       │
                    ▼         ▼  ▼       │
              ┌─────────┐ ┌──────────┐   │
              │CONFIRMED│ │CANCELLED │   │
              └─────────┘ └──────────┘   │
                                         │
                              ┌──────────┐
                              │ EXPIRED  │
                              └──────────┘
```

**Transitions:**

| From | To | Trigger | Stock Effect |
|------|----|---------|-------------|
| (new) | RESERVED | `createReservation()` | available → reserved |
| RESERVED | CONFIRMED | `confirmReservation()` | reserved → sold |
| RESERVED | CANCELLED | `cancelReservation()` | reserved → available |
| RESERVED | EXPIRED | `expireReservation()` or scheduler | reserved → available |

**Rules:**
- Only RESERVED → anything. All terminal states (CONFIRMED, CANCELLED, EXPIRED) are immutable.
- Double-confirm → 409 (`InvalidReservationStateException`)
- Confirm after expiry → 409 (`ReservationExpiredException`)
- Expiry boundary: `now >= expiresAt` (inclusive). At exactly `expiresAt`, the reservation is expired.

---

## 7. Purchase Limit Enforcement

For a user attempting to reserve quantity Q of SKU S with purchase limit L:

```
confirmedQty(S, user) + activeReservedQty(S, user) + Q  ≤  L
```

**Where it runs:**
- Inside `createReservation()`, under the same transaction and product lock
- Two aggregate queries sum quantities from the `reservations` table
- If the total exceeds L → `PurchaseLimitExceededException` (409)

**Important nuance:** Both confirmed and active reserved quantities count. A user who has already confirmed 2 units and has 1 reserved cannot reserve another — the limit checks the combined total.

**Release on cancel/expire:** When a reservation is cancelled or expired, the reserved quantity is released back to available stock, and the user's limit allocation is freed (since the aggregate query only counts RESERVED + CONFIRMED).

---

## 8. Request Flow Walkthroughs

### 8.1 Create Reservation

```
POST /api/v1/reservations
{"userId": "user123", "sku": "PHONE-15-PRO", "quantity": 1}
```

```
ReservationController.createReservation()
  │
  ├─ @Valid validates: userId not blank, sku not blank, quantity >= 1
  │
  └─ ReservationService.createReservation("user123", "PHONE-15-PRO", 1)
       │
       ├─ [TRANSACTION BEGINS]
       │
       ├─ 1. productRepository.findBySkuForUpdate("PHONE-15-PRO")
       │     → SELECT ... FROM products WHERE sku = ? FOR UPDATE
       │     → Acquires PESSIMISTIC_WRITE lock
       │     → Returns Product entity, or throws ProductNotFoundException
       │
       ├─ 2. Check availableStock >= 1
       │     → No → InsufficientStockException (409)
       │
       ├─ 3. enforcePurchaseLimit("user123", "PHONE-15-PRO", 1, purchaseLimit)
       │     → Query: SUM(quantity) FROM reservations
       │              WHERE userId='user123' AND sku='PHONE-15-PRO' AND status='RESERVED'
       │     → Query: SUM(quantity) FROM reservations
       │              WHERE userId='user123' AND sku='PHONE-15-PRO' AND status='CONFIRMED'
       │     → If confirmedQty + reservedQty + 1 > purchaseLimit → 409
       │
       ├─ 4. product.reserve(1)
       │     → availableStock -= 1, reservedStock += 1
       │     → Returns false if insufficient (second check, belt-and-suspenders)
       │
       ├─ 5. Create Reservation entity, save to DB
       │
       └─ [TRANSACTION COMMITS — lock released]
            → Returns ReservationResponse (201 Created)
```

### 8.2 Confirm Reservation

```
POST /api/v1/reservations/{id}/confirm
```

```
ReservationService.confirmReservation(id)
  │
  ├─ [TRANSACTION BEGINS]
  │
  ├─ 1. Find reservation by ID (no lock — just to get the SKU)
  │     → ReservationNotFoundException if missing
  │
  ├─ 2. productRepository.findBySkuForUpdate(sku)
  │     → Locks the product row (consistent ordering: product before reservation)
  │
  ├─ 3. reservationRepository.findByIdForUpdate(id)
  │     → Locks the reservation row
  │
  ├─ 4. reservation.confirm(now)
  │     → Checks status == RESERVED (else InvalidReservationStateException)
  │     → Checks !isExpired(now) (else ReservationExpiredException)
  │     → Sets status = CONFIRMED, confirmedAt = now
  │
  ├─ 5. product.confirm(quantity)
  │     → reservedStock -= quantity, soldStock += quantity
  │
  └─ [TRANSACTION COMMITS]
```

### 8.3 Expire Reservation (via Scheduler)

```
ExpirationScheduler runs every 5 seconds
  │
  ├─ Query: SELECT * FROM reservations
  │          WHERE status='RESERVED' AND expires_at <= now()
  │          LIMIT 100
  │          FOR UPDATE (PESSIMISTIC_WRITE)
  │
  └─ For each overdue reservation:
       │
       └─ reservationService.expireReservation(id, now)  ← new transaction per reservation
            │
            ├─ Lock reservation row
            ├─ Check not already terminal (idempotent)
            ├─ Lock product row
            ├─ reservation.expire(now) → status = EXPIRED
            └─ product.releaseReservation(quantity) → reserved → available
```

---

## 9. Testing Strategy

### 9.1 Unit Tests

**ProductTest** (11 tests) — Verifies the domain entity in isolation:
- Stock mutations (`reserve`, `confirm`, `releaseReservation`) change correct counters
- Invariant holds after every operation
- Negative/zero quantity arguments throw `IllegalArgumentException`
- Insufficient stock returns `false` (not exception)
- Edge cases: reserve at boundary (exactly available stock)

**ReservationTest** (14 tests) — Verifies the state machine:
- Creation with valid/invalid arguments
- Confirm transitions RESERVED → CONFIRMED
- Cancel transitions RESERVED → CANCELLED
- Double-confirm throws `InvalidReservationStateException`
- Confirm-after-expiry throws `ReservationExpiredException`
- Expiry boundary: `now == expiresAt` → expired (not before)
- `isExpired()` and `isTerminal()` predicates

### 9.2 Integration Tests

**InventoryIntegrationTest** (4 tests) — Full request-response:
- `GET /api/v1/inventory` returns all products
- `GET /api/v1/inventory/{sku}` returns single product
- 404 for unknown SKU
- Stock counts consistent after operations

**ReservationIntegrationTest** (12 tests) — Full lifecycle through HTTP:
- Create → 201 with correct response body
- Create with invalid SKU → 404
- Create with insufficient stock → 409
- Confirm → 200, stock reflects sold
- Cancel → 200, stock reflects released
- Get reservation → 200
- Confirm already-confirmed → 409
- Expired reservation cannot be confirmed → 409
- Verify stock invariant across operations

### 9.3 Concurrency Tests

**ConcurrencyTest** (4 tests) — The critical ones:

| Test | What It Proves |
|------|---------------|
| `shouldNotOversellUnderConcurrentReservations` | 50 threads reserve 1 unit each from 50 stock. Only 50 succeed. No oversell. |
| `shouldEnforcePurchaseLimitConcurrently` | 20 threads, same user, limit=10. At most 10 succeed. |
| `confirmAndExpireShouldNotDoubleCountStock` | Race confirm vs expire. Exactly one wins. Stock invariant holds. |
| `concurrentReservationsFromDifferentUsers` | 30 users, 50 stock. Total ≤ stock. Each user ≤ limit. |

All concurrency tests use `CountDownLatch` to release all threads simultaneously, simulating a real flash-sale burst. Each test asserts the stock invariant holds at the end.

---

## 10. Design Decisions & Trade-offs

### Pessimistic Locking vs Optimistic Locking

| | Pessimistic (chosen) | Optimistic |
|---|---|---|
| **Mechanism** | `SELECT ... FOR UPDATE` | `@Version` + retry on `OptimisticLockException` |
| **Under high contention** | Requests queue at DB | Retry storms amplify load |
| **Implementation complexity** | Simple — one annotation | Retry logic, exponential backoff |
| **Throughput** | Lower peak, predictable | Higher peak (no locks), worse under contention |
| **Deadlock risk** | Yes — mitigated by lock ordering | No deadlocks |

**Why pessimistic wins here:** Flash sales have extreme contention on hot SKUs. Optimistic locking's retry model degrades into a thundering herd. Pessimistic locking accepts the serialisation cost and gives predictable behaviour.

### UUID vs Auto-Increment IDs

UUIDs chosen for:
- No sequential guessing (security through obscurity for reservation IDs)
- No coordination needed for distributed ID generation
- Works naturally with string-based primary keys in H2

Cost: larger indexes, slightly slower joins. Acceptable for this scale.

### No Lombok

Entities (`Product`, `Reservation`) contain core business logic in their mutation methods. Lombok's `@Data` would generate `equals`/`hashCode` that interact poorly with JPA proxies and lazy loading. Explicit code makes the domain logic auditable.

### Injectable Clock

`Clock` is a Spring bean, injected into services and the loader. Tests provide `Clock.fixed(Instant.parse("..."))` for deterministic time. Production uses `Clock.systemUTC()`.

This eliminates `Thread.sleep()` in tests and makes time-dependent assertions (expiry, TTL) repeatable.

### H2 Database

H2's MVCC mode supports row-level locking close enough to PostgreSQL/MySQL for this use case. The README explicitly notes that behaviour should be validated against the production database.

---

## 11. Production Hardening Gap Analysis

What's missing before production deployment:

| Gap | Risk | Mitigation |
|-----|------|------------|
| **No authentication** | `userId` is caller-controlled | Add OAuth2/OIDC Resource Server, derive userId from principal |
| **H2 for production** | Different locking semantics, no connection pooling | Migrate to PostgreSQL/MySQL, validate locking behaviour |
| **Single-instance locking** | Pessimistic locks don't work across instances | Use DB-level atomic conditional updates or Redis distributed locks |
| **No rate limiting** | One user can hammer the API | Bucket4j + Redis or API gateway rate limiting |
| **No pagination** | Inventory listing grows unbounded | Add Spring Data `Pageable` |
| **No OpenAPI docs** | Clients lack API contract | Add springdoc-openapi |
| **No distributed tracing** | Hard to debug across services | Micrometer Tracing + Zipkin |
| **No structured logging** | Hard to correlate requests | Add MDC request IDs |

---

## 12. Quick Reference

### Key Files

| File | Role |
|------|------|
| `Product.java` | Domain entity: stock counters, mutation methods, invariant |
| `Reservation.java` | Domain entity: state machine, lifecycle transitions |
| `ReservationService.java` | Core business logic: transactions, locks, orchestration |
| `ProductRepository.java` | `findBySkuForUpdate()` — the pessimistic lock query |
| `ReservationRepository.java` | `findByIdForUpdate()`, purchase limit aggregate queries |
| `ExpirationScheduler.java` | Background expiry: scans, batches, delegates to service |
| `InventoryLoader.java` | Startup: loads `inventory.json`, idempotent |
| `GlobalExceptionHandler.java` | Maps 6 exception types → RFC 9457 Problem Details |
| `ConcurrencyTest.java` | 4 tests proving correctness under concurrent load |
| `V1__create_tables.sql` | Schema: CHECK constraints, indexes, foreign keys |

### API Surface

```
GET    /api/v1/inventory                  List all products
GET    /api/v1/inventory/{sku}            Get one product
POST   /api/v1/reservations               Create reservation
GET    /api/v1/reservations/{id}          Get reservation
POST   /api/v1/reservations/{id}/confirm  Confirm reservation
POST   /api/v1/reservations/{id}/cancel   Cancel reservation
```

### Error Codes

| Code | HTTP | Meaning |
|------|------|---------|
| `PRODUCT_NOT_FOUND` | 404 | SKU doesn't exist |
| `RESERVATION_NOT_FOUND` | 404 | Reservation ID not found |
| `INSUFFICIENT_STOCK` | 409 | Not enough available units |
| `PURCHASE_LIMIT_EXCEEDED` | 409 | User's per-SKU limit reached |
| `INVALID_RESERVATION_STATE` | 409 | Wrong state for operation |
| `RESERVATION_EXPIRED` | 409 | Reservation past its TTL |
| `VALIDATION_ERROR` | 400 | Invalid request body |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

### Database Schema (Key Columns)

**products**: `id(UUID)`, `sku(UNIQUE)`, `name`, `initial_stock`, `available_stock`, `reserved_stock`, `sold_stock`, `purchase_limit`, `version`

**reservations**: `id(UUID)`, `user_id`, `sku(FK→products)`, `quantity`, `status(RESERVED/CONFIRMED/CANCELLED/EXPIRED)`, `created_at`, `expires_at`, `confirmed_at`, `cancelled_at`, `version`

**Indexes**: `(sku)`, `(user_id, sku)`, `(expires_at, status)` — each chosen to support a specific query pattern (stock lookup, purchase limit enforcement, expiration scanning).

---

*Generated for the SDSI Agentic AI Digithon 2026 submission.*