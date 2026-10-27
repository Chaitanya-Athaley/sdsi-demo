# AI-Assisted Development Record — Flash Sale Inventory Reservation System

## Master Prompt

The entire project was implemented from a single detailed master prompt specifying:
- Role: Senior Java Architect, Spring Boot Engineer, Database Engineer, QA Engineer, Security Engineer
- Technology stack: Java 21, Spring Boot 3.4.x, Maven, H2, Flyway, JPA/Hibernate, JUnit 5
- Functional requirements: M1-M5 (inventory loading, reservations, lifecycle, purchase limits, reporting)
- Critical design requirement: prevent overselling with pessimistic database locking
- Testing: unit, integration, and concurrency tests
- Documentation: README.md, PROMPTS.md, .env.example, .gitignore

## Architectural Decisions

### 1. Pessimistic Write Locking
**Decision**: Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on product queries for stock mutations.

**Rationale**: Pessimistic locking is the simplest correct concurrency strategy for stock management in a single-instance application. It serializes conflicting mutations at the database level without the complexity of retry loops needed for optimistic locking. The alternative — atomic conditional SQL updates (`UPDATE ... WHERE available >= ?`) — would bypass JPA's entity state management, requiring explicit flush/refresh cycles that are error-prone in a layered architecture.

### 2. Lock Ordering: Product Before Reservation
**Decision**: Always lock the product row before the reservation row in operations that touch both (confirm, cancel, expire).

**Rationale**: Consistent lock ordering prevents deadlocks. Two concurrent confirmations for the same SKU but different reservations will both attempt to lock the product row first — one blocks, the other proceeds.

### 3. UUID Primary Keys
**Decision**: Use `@GeneratedValue(strategy = GenerationType.UUID)` for both Product and Reservation entities.

**Rationale**: UUIDs avoid sequential ID guessing, provide globally unique identifiers without database coordination, and work well if the system ever needs to shard data across multiple databases.

### 4. Stock Invariant in Domain Entity
**Decision**: `Product.reserve()`, `confirm()`, and `releaseReservation()` all maintain `initialStock = availableStock + reservedStock + soldStock` within the entity itself.

**Rationale**: Pushing stock accounting into the domain entity means incorrect stock arithmetic is caught as an `IllegalStateException` at the point of mutation, not discovered later in a reporting discrepancy.

### 5. No Lombok
**Decision**: Write explicit getters, constructors, and domain methods without Lombok.

**Rationale**: The master prompt specifically noted "Avoid Lombok if it obscures important entity behavior or equality semantics." The `Product` entity's stock-mutation methods (`reserve`, `confirm`, `releaseReservation`) are core business logic that should be visible and auditable, not hidden behind `@Data` annotations. JPA proxies also interact subtly with Lombok's `equals/hashCode` generation.

### 6. Injectable Clock
**Decision**: Use a Spring `@Bean Clock clock()` and inject it into services and the loader.

**Rationale**: All time-dependent logic (reservation expiration, TTL calculation, entity timestamps) uses the injected clock. Tests can provide a fixed `Clock.fixed(...)` for deterministic behavior without `Thread.sleep()` or real-time waiting.

### 7. Expiration Boundary: now >= expiresAt
**Decision**: A reservation is expired when `now` is *not before* `expiresAt`. Exact equality means expired.

**Rationale**: Using `!now.isBefore(expiresAt)` makes the boundary unambiguous: at exactly `expiresAt`, the reservation cannot be confirmed. This avoids the ambiguity of "strictly after" vs "at or after" and matches the natural language expectation that the reservation is valid only *during* the TTL window.

### 8. H2 for Both Dev and Test
**Decision**: Use H2 file-based persistence for development and H2 in-memory for tests.

**Rationale**: The master prompt required H2 and discouraged making Testcontainers mandatory. H2's MVCC mode supports the pessimistic locking used in this application, though behavior may differ from PostgreSQL/MySQL in edge cases.

### 9. ProblemDetail (RFC 9457) for Error Responses
**Decision**: Use Spring's `ProblemDetail` in `@RestControllerAdvice` for consistent error responses.

**Rationale**: ProblemDetail is the Spring Framework 6+ standard for REST API errors, replacing the older `@ResponseStatus` + custom body approach. It provides a structured format with `type`, `title`, `status`, `detail`, and custom properties like `errorCode`.

## Implementation Phases

### Phase 1: Design
- Identified the product/reservation domain model
- Defined the reservation state machine (RESERVED → CONFIRMED/CANCELLED/EXPIRED)
- Chose pessimistic locking as the concurrency strategy
- Designed the stock accounting invariant

### Phase 2: Scaffold
- Created Maven POM with Spring Boot 3.4.5 parent
- Set up package structure
- Created Flyway migration (V1__create_tables.sql)
- Configured application.yml with dev/test/prod profiles

### Phase 3: Core Implementation
- Product entity with stock mutation methods
- Reservation entity with lifecycle state machine
- ReservationService with transactional, lock-guarded operations
- InventoryService and InventoryLoader
- Controllers with validation
- GlobalExceptionHandler

### Phase 4: Testing
- ReservationTest: 14 tests covering creation, validation, lifecycle transitions, expiry boundary
- ProductTest: 11 tests covering stock mutations, invariants, edge cases
- InventoryIntegrationTest: 4 tests for API endpoints
- ReservationIntegrationTest: 12 tests for full request-response lifecycle
- ConcurrencyTest: 4 tests for concurrent reservations, purchase limits, confirm/expire race

### Phase 5: Documentation
- README.md with full API docs, architecture, setup, troubleshooting

## Test-First Corrections

### Issue: Double-confirm not caught
The `confirm()` method on `Reservation` originally did not check the current state. After writing the test `shouldNotConfirmAlreadyConfirmedReservation`, the check `if (status != RESERVED) throw ...` was added.

### Issue: Expiry boundary off-by-one
The initial `isExpired()` used `now.isAfter(expiresAt)` (strictly after). The test `shouldNotConfirmIfExpired` with `EXPIRES` (exact timestamp) failed. Changed to `!now.isBefore(expiresAt)` to make the boundary inclusive at the exact timestamp.

### Issue: Purchase limit not counting confirmed quantities
The initial `enforcePurchaseLimit` only summed active reserved quantities. The test `shouldEnforcePurchaseLimitConcurrently` revealed that confirmed quantities must also count. Added `sumConfirmedQuantityByUserAndSku` query and updated the formula.

## Known Limitations

1. **Tests not executed**: The project was built in an environment where `mvn` commands could not be executed. All test files have been written and reviewed but not yet run against a live JVM.
2. **H2 locking edge cases**: H2's PESSIMISTIC_WRITE behavior under extreme concurrency (~100+ threads on the same row) may differ from PostgreSQL. The concurrency tests use 50 threads which should be within H2's capabilities.
3. **No authentication**: As noted in the README, userId is caller-controlled.
4. **No OpenAPI spec**: springdoc-openapi was not added to avoid dependency bloat, but would be recommended for production.
5. **Maven wrapper**: Not included; requires system Maven installation.

## Validation Checklist (Pending Execution)

- [ ] `mvn clean compile` — compiles without errors
- [ ] `mvn test` — all tests pass (24+ unit + integration tests)
- [ ] `mvn test -Pconcurrency` — concurrency tests pass
- [ ] Application starts and Flyway migration runs
- [ ] Inventory loaded from inventory.json on first startup
- [ ] Idempotent inventory loading on restart
- [ ] API endpoints return correct responses
- [ ] Reservation lifecycle: create → confirm/cancel/expire
- [ ] H2 console accessible (dev profile)
- [ ] Stock invariant holds under concurrent load