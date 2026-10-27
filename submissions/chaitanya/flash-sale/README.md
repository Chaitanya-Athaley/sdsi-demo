# Flash Sale Inventory Reservation System

A production-quality inventory reservation system for flash sales, built with Spring Boot 3, Java 21, and H2.

## Overview

This system prevents overselling during flash sales by using pessimistic locking at the database level to serialize concurrent stock mutations. It supports temporary stock reservations with a configurable TTL, a full reservation lifecycle (reserve → confirm/cancel/expire), per-user purchase limits enforced transactionally, and accurate inventory reporting with a verifiable stock-accounting invariant.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     API Layer (REST)                     │
│  InventoryController         ReservationController       │
│  DTOs (request/response)     @Valid validation           │
│  GlobalExceptionHandler → ProblemDetail (RFC 9457)      │
├─────────────────────────────────────────────────────────┤
│                  Application/Service Layer               │
│  InventoryService            ReservationService           │
│  Business rules, transactions, lifecycle orchestration   │
│  @Transactional + pessimistic write locks                │
├─────────────────────────────────────────────────────────┤
│                     Domain Layer                         │
│  Product (entity, stock invariants)                      │
│  Reservation (entity, state machine)                     │
│  ReservationStatus enum, BusinessException hierarchy     │
├─────────────────────────────────────────────────────────┤
│                    Persistence Layer                     │
│  ProductRepository          ReservationRepository        │
│  PESSIMISTIC_WRITE locks    H2 + Flyway migrations       │
│  JPA/Hibernate              indexes, constraints         │
├─────────────────────────────────────────────────────────┤
│                  Infrastructure                          │
│  ExpirationScheduler (@Scheduled)                        │
│  InventoryLoader (idempotent startup)                    │
│  SecurityConfig (dev-focused, stateless)                  │
│  Clock bean (injectable for testing)                     │
└─────────────────────────────────────────────────────────┘
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Pessimistic write locking** | Simplest correct concurrency strategy for stock mutations. Each SKU row is locked with `PESSIMISTIC_WRITE` before mutation, serializing conflicting requests. An atomic conditional SQL update (`UPDATE ... WHERE available >= ?`) is a valid alternative but pessimistic locking integrates more naturally with JPA's entity lifecycle. |
| **Lock ordering: product → reservation** | When both product and reservation rows need locks (confirm/cancel/expire), the product is always locked first. This prevents deadlocks when multiple operations contend for the same SKU. |
| **UUID primary keys** | String-based UUIDs avoid sequential ID guessing and work well in distributed scenarios (though the current pessimistic-lock strategy would need to change for multi-instance deployments). |
| **Stock accounting in Product entity** | `initialStock = availableStock + reservedStock + soldStock` is always enforced in Java and checked in tests. H2 check constraints provide a database-level safety net. |
| **ttl boundary: `now >= expiresAt` = expired** | A reservation expires at exactly its expiration timestamp. This is deterministic and avoids edge cases with sub-second clock granularity. |
| **No Lombok** | Entity equality semantics, constructors, and invariants are explicit in code. This avoids obscured behavior with proxy objects and lazy loading. |
| **H2 database** | H2 is used for both dev and test. The concurrency strategy works with H2's MVCC and row-level locking, but should be validated against PostgreSQL/MySQL for production. |

## Technology Stack

- Java 21 (Eclipse Temurin)
- Spring Boot 3.4.5
- Spring Data JPA + Hibernate 6
- H2 Database (file-based for dev, in-memory for tests)
- Flyway for schema migrations
- Jakarta Bean Validation
- Spring Security (minimal dev configuration)
- Spring Boot Actuator
- JUnit 5 + Mockito + Spring Boot Test
- Maven 3

## Prerequisites

- Java 21 or newer
- Maven 3.8+

## Repository Structure

```
flash-sale/
├── .env.example               # Environment variable documentation
├── .gitignore
├── pom.xml                     # Maven build
├── README.md                   # This file
├── PROMPTS.md                  # AI-assisted development record
├── src/
│   ├── main/
│   │   ├── java/com/example/flashsale/
│   │   │   ├── FlashSaleApplication.java
│   │   │   ├── config/
│   │   │   │   ├── AppConfig.java          # Clock bean
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   └── SecurityConfig.java
│   │   │   ├── controller/
│   │   │   │   ├── InventoryController.java
│   │   │   │   └── ReservationController.java
│   │   │   ├── dto/
│   │   │   │   ├── request/CreateReservationRequest.java
│   │   │   │   └── response/
│   │   │   │       ├── InventoryResponse.java
│   │   │   │       └── ReservationResponse.java
│   │   │   ├── domain/
│   │   │   │   ├── entity/
│   │   │   │   │   ├── Product.java
│   │   │   │   │   └── Reservation.java
│   │   │   │   ├── enums/ReservationStatus.java
│   │   │   │   └── exception/
│   │   │   │       ├── BusinessException.java
│   │   │   │       ├── InsufficientStockException.java
│   │   │   │       ├── InvalidReservationStateException.java
│   │   │   │       ├── ProductNotFoundException.java
│   │   │   │       ├── PurchaseLimitExceededException.java
│   │   │   │       ├── ReservationExpiredException.java
│   │   │   │       └── ReservationNotFoundException.java
│   │   │   ├── repository/
│   │   │   │   ├── ProductRepository.java
│   │   │   │   └── ReservationRepository.java
│   │   │   ├── scheduler/ExpirationScheduler.java
│   │   │   └── service/
│   │   │       ├── InventoryLoader.java
│   │   │       ├── InventoryService.java
│   │   │       └── ReservationService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── inventory.json            # Sample product data
│   │       └── db/migration/
│   │           └── V1__create_tables.sql
│   └── test/
│       ├── java/com/example/flashsale/
│       │   ├── config/TestConfig.java
│       │   ├── domain/entity/
│       │   │   ├── ProductTest.java
│       │   │   └── ReservationTest.java
│       │   └── integration/
│       │       ├── ConcurrencyTest.java
│       │       ├── InventoryIntegrationTest.java
│       │       └── ReservationIntegrationTest.java
│       └── resources/application.yml
└── data/                         # H2 file database (gitignored)
```

## Setup Instructions

### 1. Configure environment

```bash
# Copy the example config
cp .env.example .env
# Edit values if needed (defaults work for local dev)
```

Spring Boot does **not** automatically load `.env` files. Set environment variables before starting:

```bash
# Option A: Export manually
export SPRING_PROFILES_ACTIVE=dev
export SERVER_PORT=8080

# Option B: Use your IDE's env-file plugin
# IntelliJ: install "EnvFile" plugin and point to .env
```

### 2. Build the project

```bash
mvn clean compile
```

### 3. Run the application

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8080`.

### 4. Verify health

```bash
curl http://localhost:8080/actuator/health
```

## Sample Inventory

The application loads 10 sample products from `src/main/resources/inventory.json` on first startup:

| SKU | Name | Stock | Limit |
|-----|------|-------|-------|
| PHONE-15-PRO | Phone 15 Pro 256GB | 100 | 2 |
| PHONE-15 | Phone 15 128GB | 200 | 3 |
| LAPTOP-X1 | Laptop X1 16GB | 50 | 1 |
| TABLET-S9 | Tablet S9 128GB | 75 | 2 |
| WATCH-U2 | Smartwatch U2 45mm | 150 | 5 |
| BUDS-PRO2 | Earbuds Pro 2 | 500 | 10 |
| CHARGER-65W | 65W GaN Charger | 1000 | 20 |
| CABLE-USBC | USB-C to USB-C Cable 2m | 2000 | 50 |
| STAND-MAG | Magnetic Charging Stand | 300 | 5 |
| CASE-LEATHER | Leather Phone Case | 80 | 3 |

Initialization is idempotent: restarts will not reset inventory or insert duplicate products.

## H2 Console

When running with the `dev` profile:

1. Navigate to `http://localhost:8080/h2-console`
2. JDBC URL: `jdbc:h2:file:./data/flashsale`
3. Username: `sa`, Password: *(empty)*

The console is disabled in the `prod` and `test` profiles.

## Running Tests

### All tests except concurrency

```bash
mvn test
```

### Concurrency tests only

```bash
mvn test -Pconcurrency
```

### All tests including concurrency

```bash
mvn test -Pconcurrency -Dtest="*Test,*ConcurrencyTest"
```

Or run directly:

```bash
mvn test -Dtest="ConcurrencyTest"
```

## API Documentation

Base URL: `http://localhost:8080/api/v1`

### Error Response Format

All errors use [Problem Details (RFC 9457)](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "type": "about:blank",
  "title": "Insufficient Stock",
  "status": 409,
  "detail": "Insufficient stock for SKU PHONE-15-PRO: requested 200, available 100",
  "instance": "/api/v1/reservations",
  "errorCode": "INSUFFICIENT_STOCK"
}
```

### Error Codes

| HTTP Status | Error Code | Description |
|-------------|-----------|-------------|
| 400 | VALIDATION_ERROR | Invalid request body/parameters |
| 400 | BAD_REQUEST | Illegal argument |
| 404 | PRODUCT_NOT_FOUND | SKU does not exist |
| 404 | RESERVATION_NOT_FOUND | Reservation ID not found |
| 409 | INSUFFICIENT_STOCK | Not enough available stock |
| 409 | PURCHASE_LIMIT_EXCEEDED | Per-user limit reached |
| 409 | INVALID_RESERVATION_STATE | Invalid lifecycle transition |
| 409 | RESERVATION_EXPIRED | Reservation past expiration |
| 500 | INTERNAL_ERROR | Unexpected server error |

### GET /api/v1/inventory

List all products with current stock levels.

```bash
curl http://localhost:8080/api/v1/inventory
```

Response:
```json
[
  {
    "sku": "PHONE-15-PRO",
    "name": "Phone 15 Pro 256GB",
    "initialStock": 100,
    "availableStock": 100,
    "reservedStock": 0,
    "soldStock": 0,
    "purchaseLimit": 2
  }
]
```

### GET /api/v1/inventory/{sku}

Get stock details for a specific SKU.

```bash
curl http://localhost:8080/api/v1/inventory/PHONE-15-PRO
```

### POST /api/v1/reservations

Create a new reservation.

```bash
curl -X POST http://localhost:8080/api/v1/reservations \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123", "sku": "PHONE-15-PRO", "quantity": 1}'
```

Response (201 Created):
```json
{
  "id": "a1b2c3d4-e5f6-...",
  "userId": "user123",
  "sku": "PHONE-15-PRO",
  "quantity": 1,
  "status": "RESERVED",
  "createdAt": "2026-10-27T10:00:00Z",
  "expiresAt": "2026-10-27T10:02:00Z"
}
```

### GET /api/v1/reservations/{reservationId}

Retrieve reservation details.

```bash
curl http://localhost:8080/api/v1/reservations/a1b2c3d4-e5f6-...
```

### POST /api/v1/reservations/{reservationId}/confirm

Confirm a reservation (moves reserved → sold stock).

```bash
curl -X POST http://localhost:8080/api/v1/reservations/a1b2c3d4-e5f6-.../confirm
```

### POST /api/v1/reservations/{reservationId}/cancel

Cancel a reservation (releases reserved → available stock).

```bash
curl -X POST http://localhost:8080/api/v1/reservations/a1b2c3d4-e5f6-.../cancel
```

## Reservation Lifecycle

```
                    ┌──────────┐
                    │ RESERVED │
                    └────┬──┬──┘
          ┌─────────────┘  │  └─────────────┐
          ▼                ▼                ▼
    ┌──────────┐    ┌──────────┐     ┌──────────┐
    │CONFIRMED │    │CANCELLED │     │ EXPIRED  │
    └──────────┘    └──────────┘     └──────────┘

Stock: reserved → sold      Stock: reserved → available
```

- **RESERVED**: Stock is held for the user. Reduces `availableStock`, increases `reservedStock`.
- **CONFIRMED**: Purchase is final. Reduces `reservedStock`, increases `soldStock`.
- **CANCELLED**: User releases the hold. Increases `availableStock`, reduces `reservedStock`.
- **EXPIRED**: TTL elapsed. Same stock effect as cancellation. Triggered by scheduled job or during transactional operations.

**Expiration boundary**: A reservation is expired when `now >= expiresAt` (inclusive of the exact timestamp). It cannot be confirmed at or after its `expiresAt`.

Only a RESERVED reservation can transition; all terminal states are immutable. Double-confirm, double-cancel, or confirm-after-expire all fail with 409.

## Stock Accounting Invariant

```
initialStock = availableStock + reservedStock + soldStock
```

This invariant is:
- Enforced in the `Product` domain entity (all mutations preserve it).
- Checked in unit tests for each operation.
- Guarded by H2 CHECK constraints on non-negative column values.

## Purchase Limit Rule

For a given user and SKU:

```
confirmedQuantity + activeReservedQuantity + requestedQuantity <= purchaseLimit
```

- Confirmed purchases count permanently toward the limit.
- Active (RESERVED) reservations count while they remain active.
- Cancellation or expiration releases the user's limit allocation.
- Enforced transactionally under the same pessimistic lock as stock mutation.

**Important**: The `userId` field is caller-controlled (not authenticated). In production, derive the user ID from an OAuth2/OIDC principal.

## Concurrency Strategy

### Pessimistic Write Locking

All stock-mutating operations acquire a `PESSIMISTIC_WRITE` lock on the `products` row for the affected SKU:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.sku = :sku")
Optional<Product> findBySkuForUpdate(@Param("sku") String sku);
```

This lock is held until the transaction commits or rolls back. Any concurrent request for the same SKU blocks until the first transaction completes.

### Lock Ordering

When an operation needs both product and reservation locks (confirm, cancel, expire):

1. Product row locked first (`findBySkuForUpdate`)
2. Reservation row locked second (`findByIdForUpdate`)

This consistent ordering prevents deadlocks.

### Transaction Boundaries

Each service method (`createReservation`, `confirmReservation`, `cancelReservation`, `expireReservation`) runs in its own `@Transactional` boundary. Stock validation, mutation, and reservation state changes happen atomically within a single transaction.

### Multi-Instance Deployment

The current pessimistic locking strategy works for a single application instance. For a distributed deployment:

- Replace pessimistic locks with **database-level atomic conditional updates** (`UPDATE products SET available = available - ? WHERE sku = ? AND available >= ?`).
- Or use **Redis-based distributed locking** (Redisson) with the database as the source of truth.
- Or use a **message queue** to serialize mutations per SKU to a single consumer.

### H2 Limitations

H2 supports row-level locking in its MVCC mode but may not behave identically to PostgreSQL or MySQL under extreme concurrency. Validate the locking behavior against your production database before deployment.

## Performance Notes

- **Indexes**: SKU lookups, user/SKU purchase-limit queries, and expiration scans are indexed.
- **Expiration batches**: The scheduler processes at most `flash-sale.expiration.batch-size` (default 100) reservations per run, configurable.
- **Transaction scope**: Each reservation lifecycle operation is its own transaction — no long-held locks across user interactions.
- **No N+1 queries**: JPA `open-in-view` is disabled. All queries use explicit joins or aggregate functions.
- **Pagination**: Inventory listing is not paginated by default (10 products in sample data). Add Spring Data `Pageable` for production-scale catalogs.

## Configuration Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `dev` | Active Spring profile |
| `SERVER_PORT` | `8080` | HTTP server port |
| `FLASH_SALE_DB_URL` | `jdbc:h2:file:./data/flashsale;...` | H2 JDBC URL |
| `FLASH_SALE_DB_USERNAME` | `sa` | Database username |
| `FLASH_SALE_DB_PASSWORD` | *(empty)* | Database password |
| `H2_CONSOLE_ENABLED` | `true` | Enable H2 web console |
| `H2_CONSOLE_PATH` | `/h2-console` | H2 console URL path |
| `RESERVATION_TTL_SECONDS` | `120` | Default reservation TTL |
| `EXPIRATION_INTERVAL_MS` | `5000` | Expiration scan interval |
| `LOGGING_LEVEL` | `INFO` | Root log level |
| `JPA_SHOW_SQL` | `false` | Log SQL statements |

## Known Limitations

1. **No authentication**: The `userId` field is caller-controlled. This is a demo/development application. Production must derive user identity from an OAuth2/OIDC principal.
2. **H2 concurrency**: H2's locking semantics differ from PostgreSQL/MySQL. Validate against your target database.
3. **Single instance**: Pessimistic locking works for a single app instance. See "Multi-Instance Deployment" above.
4. **No rate limiting**: Production should add rate limiting (e.g., Bucket4j, Redis) to prevent abuse.
5. **No pagination on inventory**: Sample data is small. Add `Pageable` for production.
6. **No distributed tracing**: Add Micrometer Tracing + Zipkin for observability in production.

## Production Hardening Recommendations

1. Replace H2 with PostgreSQL or MySQL.
2. Add OAuth2/OIDC authentication (Spring Security OAuth2 Resource Server).
3. Add rate limiting (Bucket4j + Redis or API gateway).
4. Add Micrometer metrics and distributed tracing.
5. Consider database-level atomic conditional updates instead of ORM-level pessimistic locks for distributed deployments.
6. Add integration tests using Testcontainers with the target production database.
7. Add OpenAPI/Swagger documentation (springdoc-openapi).
8. Configure CORS properly for your frontend origin(s).
9. Add request ID propagation and structured logging (MDC).
10. Set up database connection pooling tuning (HikariCP defaults are reasonable for moderate load).

## Troubleshooting

### "Table PRODUCTS not found" on startup
Flyway may not have run. Ensure `src/main/resources/db/migration/V1__create_tables.sql` exists and `spring.flyway.enabled=true`.

### Inventory resets on restart
Check that `inventory.json` loading is skipped when products already exist (idempotent loader). Delete `data/` directory only if you intentionally want to reset.

### H2 console "Database not found"
Ensure the JDBC URL matches your running instance. For dev profile: `jdbc:h2:file:./data/flashsale`.

### Concurrency tests timeout
Increase the timeout in `ConcurrencyTest` or reduce thread count. H2 may be slow under high concurrency in test mode.