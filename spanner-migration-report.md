# FTGO Application - Google Cloud Spanner Migration Report

## Executive Summary

This report provides a comprehensive migration strategy for moving the FTGO microservices application from MySQL to Google Cloud Spanner. The migration requires careful consideration of Spanner's distributed nature, different locking mechanisms, and schema design patterns.

**Key Migration Challenges**:
- Auto-increment primary keys → UUID/timestamp-based keys
- Pessimistic locking (implicit) → Explicit optimistic locking
- Single-node transactions → Distributed transactions
- Schema design for hotspot avoidance
- JPA/Hibernate compatibility adjustments

**Migration Complexity**: MEDIUM-HIGH
- **AI-Automatable**: ~60-70% of changes
- **Manual Required**: ~30-40% of changes (critical design decisions)

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Spanner-Specific Migration Challenges](#2-spanner-specific-migration-challenges)
3. [Code Changes Required](#3-code-changes-required)
4. [AI-Automatable Changes](#4-ai-automatable-changes)
5. [Manual Changes Required](#5-manual-changes-required)
6. [Query Structure Changes](#6-query-structure-changes)
7. [Locking Strategy Migration](#7-locking-strategy-migration)
8. [Service-by-Service Migration Plan](#8-service-by-service-migration-plan)
9. [Testing Strategy](#9-testing-strategy)
10. [Rollback Plan](#10-rollback-plan)

---

## 1. Current State Analysis

### 1.1 MySQL Usage Patterns

| Service | Database | Entities | Primary Key Strategy | Transactions | Locking |
|---------|----------|----------|---------------------|--------------|---------|
| **ftgo-consumer-service** | ftgo_consumer_service | Consumer | Auto-increment | JPA implicit | @Version (optimistic) |
| **ftgo-order-service** | ftgo_order_service | Order, Restaurant (replica) | Auto-increment | JPA implicit | @Version (optimistic) |
| **ftgo-kitchen-service** | ftgo_kitchen_service | Ticket, Restaurant (replica) | Manual ID (from Order) | JPA implicit | None (state machine) |
| **ftgo-restaurant-service** | ftgo_restaurant_service | Restaurant, MenuItem | Auto-increment | JPA implicit | None |
| **ftgo-accounting-service** | ftgo_accounting_service | Account (event-sourced) | Event store | Eventuate Client | Event sourcing |
| **ftgo-delivery-service** | ftgo_delivery_service | Delivery, Restaurant (replica) | Auto-increment | JPA implicit | None |

### 1.2 Current JPA Patterns

**Primary Key Generation**:
```java
@Id
@GeneratedValue  // Uses AUTO_INCREMENT in MySQL
private Long id;
```

**Optimistic Locking (Already Present)**:
```java
@Version
private Long version;  // Order entity already has this
```

**Embedded Collections**:
```java
@ElementCollection
@CollectionTable(name = "order_line_items")
private List<OrderLineItem> lineItems;
```

**Relationships**:
- Mostly denormalized (database-per-service pattern)
- Few foreign key constraints
- Restaurant data replicated across services

---

## 2. Spanner-Specific Migration Challenges

### 2.1 Critical Spanner Constraints

| Challenge | MySQL Behavior | Spanner Requirement | Impact |
|-----------|----------------|---------------------|--------|
| **Auto-increment PKs** | Sequential IDs (1, 2, 3...) | Causes write hotspots | HIGH - Schema redesign |
| **Timestamp-based PKs** | ORDER BY timestamp DESC | Causes write hotspots | HIGH - Schema redesign |
| **Foreign Keys** | Supported | Limited support, use INTERLEAVE instead | MEDIUM |
| **Transactions** | Single-node ACID | Distributed with 2PC | MEDIUM |
| **Locking** | Row-level locks | Optimistic by default | MEDIUM |
| **Auto DDL** | JPA can create schema | Manual DDL required | LOW - Already disabled in prod |
| **JOIN Performance** | Fast joins | Prefer denormalization | LOW - Already denormalized |

### 2.2 Hotspot Issues in Current Schema

**High Risk** (Sequential IDs causing write hotspots):

1. **Order Table**:
   ```sql
   -- CURRENT (MySQL)
   CREATE TABLE orders (
     id BIGINT AUTO_INCREMENT PRIMARY KEY,  -- HOTSPOT!
     ...
   );
   ```

   **Problem**: All new orders get sequential IDs (1, 2, 3...), causing all writes to same Spanner split.

2. **Consumer Table**:
   ```sql
   -- CURRENT (MySQL)
   CREATE TABLE consumers (
     id BIGINT AUTO_INCREMENT PRIMARY KEY,  -- HOTSPOT!
     ...
   );
   ```

3. **Restaurant Table**:
   ```sql
   -- CURRENT (MySQL)
   CREATE TABLE restaurants (
     id BIGINT AUTO_INCREMENT PRIMARY KEY,  -- HOTSPOT!
     ...
   );
   ```

**Medium Risk** (Timestamp-based queries):
- Orders sorted by creation time
- Tickets by creation/preparation time

---

## 3. Code Changes Required

### 3.1 Schema Changes (Per Service)

#### Change 1: Primary Key Strategy

**Current (MySQL)**:
```sql
CREATE TABLE orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  version BIGINT,
  state VARCHAR(50),
  consumer_id BIGINT,
  restaurant_id BIGINT,
  ...
);
```

**Target (Spanner)**:
```sql
CREATE TABLE orders (
  id STRING(36) NOT NULL,  -- UUID
  version INT64,
  state STRING(50),
  consumer_id STRING(36),
  restaurant_id STRING(36),
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  ...
) PRIMARY KEY (id);

-- Secondary index for queries
CREATE INDEX idx_orders_created
ON orders(created_timestamp DESC, id);
```

**Alternative (Timestamp + UUID for natural ordering)**:
```sql
CREATE TABLE orders (
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  id STRING(36) NOT NULL,  -- UUID
  version INT64,
  ...
) PRIMARY KEY (created_timestamp DESC, id);
```

#### Change 2: Child Tables (Interleaving)

**Current (MySQL)**:
```sql
CREATE TABLE order_line_items (
  order_id BIGINT,
  menu_item_id VARCHAR(255),
  quantity INT,
  price BIGINT,
  ...
);
```

**Target (Spanner - Interleaved)**:
```sql
CREATE TABLE order_line_items (
  order_id STRING(36) NOT NULL,
  line_item_id STRING(36) NOT NULL,  -- Add synthetic key
  menu_item_id STRING(255),
  quantity INT64,
  price INT64,
  ...
) PRIMARY KEY (order_id, line_item_id),
  INTERLEAVE IN PARENT orders ON DELETE CASCADE;
```

**Benefit**: Co-locates line items with parent order for efficient reads.

#### Change 3: Version Column for Optimistic Locking

**Current (Order already has it)**:
```java
@Version
private Long version;  // ✓ Already present
```

**Current (Ticket - MISSING)**:
```java
@Entity
@Table(name = "tickets")
public class Ticket {
  @Id
  private Long id;
  // NO @Version field!  ⚠️
}
```

**Target (Add version to all entities)**:
```java
@Entity
@Table(name = "tickets")
public class Ticket {
  @Id
  private String id;  // UUID

  @Version
  private Long version;  // ✓ Add this

  @Enumerated(EnumType.STRING)
  private TicketState state;
  ...
}
```

---

### 3.2 Java Entity Changes

#### Entity: Order (ftgo-order-service)

**BEFORE (MySQL)**:
```java
@Entity
@Table(name = "orders")
@Access(AccessType.FIELD)
public class Order {

  @Id
  @GeneratedValue  // AUTO_INCREMENT
  private Long id;

  @Version
  private Long version;  // Already has optimistic locking ✓

  @Enumerated(EnumType.STRING)
  private OrderState state;

  private Long consumerId;
  private Long restaurantId;

  @Embedded
  private OrderLineItems orderLineItems;

  // ...
}
```

**AFTER (Spanner)**:
```java
@Entity
@Table(name = "orders")
@Access(AccessType.FIELD)
public class Order {

  @Id
  @GeneratedValue(generator = "uuid2")
  @GenericGenerator(name = "uuid2", strategy = "uuid2")
  @Column(columnDefinition = "STRING(36)")
  private String id;  // Changed: Long → String (UUID)

  @Version
  private Long version;  // Keep optimistic locking

  @Enumerated(EnumType.STRING)
  @Column(length = 50)
  private OrderState state;

  @Column(columnDefinition = "STRING(36)")
  private String consumerId;  // Changed: Long → String

  @Column(columnDefinition = "STRING(36)")
  private String restaurantId;  // Changed: Long → String

  @Embedded
  private OrderLineItems orderLineItems;

  @Column(nullable = false, columnDefinition = "TIMESTAMP")
  @CreationTimestamp
  private Timestamp createdAt;  // Add for indexing

  // ...
}
```

**Code Impact**:
- Change `Long id` → `String id` in 6 places
- Update `getId()` return type
- Update `setId()` parameter type
- Update constructors
- Update saga data objects (pass String IDs)

---

#### Entity: Consumer (ftgo-consumer-service)

**BEFORE**:
```java
@Entity
@Table(name = "consumers")
public class Consumer {
  @Id
  @GeneratedValue
  private Long id;

  @Embedded
  private PersonName name;

  // NO @Version field ⚠️
}
```

**AFTER**:
```java
@Entity
@Table(name = "consumers")
public class Consumer {
  @Id
  @GeneratedValue(generator = "uuid2")
  @GenericGenerator(name = "uuid2", strategy = "uuid2")
  @Column(columnDefinition = "STRING(36)")
  private String id;

  @Version
  private Long version;  // ADD optimistic locking

  @Embedded
  private PersonName name;

  @Column(nullable = false, columnDefinition = "TIMESTAMP")
  @CreationTimestamp
  private Timestamp createdAt;
}
```

---

#### Entity: Ticket (ftgo-kitchen-service)

**BEFORE**:
```java
@Entity
@Table(name = "tickets")
public class Ticket {
  @Id
  private Long id;  // Set from Order ID (not auto-generated)

  @Enumerated(EnumType.STRING)
  private TicketState state;

  private Long restaurantId;

  @ElementCollection
  @CollectionTable(name = "ticket_line_items")
  private List<TicketLineItem> lineItems;

  // NO @Version field ⚠️
}
```

**AFTER**:
```java
@Entity
@Table(name = "tickets")
public class Ticket {
  @Id
  @Column(columnDefinition = "STRING(36)")
  private String id;  // Still from Order, but now UUID

  @Version
  private Long version;  // ADD optimistic locking

  @Enumerated(EnumType.STRING)
  @Column(length = 50)
  private TicketState state;

  @Column(columnDefinition = "STRING(36)")
  private String restaurantId;

  @ElementCollection
  @CollectionTable(name = "ticket_line_items")
  private List<TicketLineItem> lineItems;

  @Column(nullable = false, columnDefinition = "TIMESTAMP")
  private Timestamp createdAt;
}
```

---

#### Entity: Restaurant (ftgo-restaurant-service)

**BEFORE**:
```java
@Entity
@Table(name = "restaurants")
public class Restaurant {
  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @Embedded
  private RestaurantMenu menu;

  // NO @Version field ⚠️
}
```

**AFTER**:
```java
@Entity
@Table(name = "restaurants")
public class Restaurant {
  @Id
  @GeneratedValue(generator = "uuid2")
  @GenericGenerator(name = "uuid2", strategy = "uuid2")
  @Column(columnDefinition = "STRING(36)")
  private String id;

  @Version
  private Long version;

  @Column(length = 255)
  private String name;

  @Embedded
  private RestaurantMenu menu;

  @Column(nullable = false, columnDefinition = "TIMESTAMP")
  @CreationTimestamp
  private Timestamp createdAt;
}
```

---

### 3.3 Repository Changes

**No Changes Required** ✓

```java
// Works for both Long and String IDs
public interface OrderRepository extends CrudRepository<Order, String> {
  // Just change generic type from Long → String
}
```

---

### 3.4 Service Layer Changes

**BEFORE**:
```java
public class OrderService {
  public EntityWithIdAndVersion<Order> createOrder(long consumerId, long restaurantId, ...) {
    Restaurant restaurant = restaurantRepository.findById(restaurantId)  // Long
      .orElseThrow(() -> new RestaurantNotFoundException(restaurantId));

    ResultWithDomainEvents<Order, OrderDomainEvent> result =
      Order.createOrder(consumerId, restaurant, ...);

    Order order = orderRepository.save(result.result);

    // order.getId() returns Long
    return new EntityWithIdAndVersion<>(order, order.getId(), order.getVersion());
  }
}
```

**AFTER**:
```java
public class OrderService {
  public EntityWithIdAndVersion<Order> createOrder(String consumerId, String restaurantId, ...) {
    Restaurant restaurant = restaurantRepository.findById(restaurantId)  // String
      .orElseThrow(() -> new RestaurantNotFoundException(restaurantId));

    ResultWithDomainEvents<Order, OrderDomainEvent> result =
      Order.createOrder(consumerId, restaurant, ...);

    Order order = orderRepository.save(result.result);

    // order.getId() returns String (UUID)
    return new EntityWithIdAndVersion<>(order, order.getId(), order.getVersion());
  }
}
```

**Changes**:
- Parameter types: `long` → `String`
- Variable types: `Long` → `String`
- ID comparisons: `id == otherId` → `id.equals(otherId)`

---

### 3.5 Controller/API Changes

**BEFORE**:
```java
@RestController
@RequestMapping("/orders")
public class OrderController {

  @GetMapping("/{orderId}")
  public GetOrderResponse getOrder(@PathVariable long orderId) {
    Optional<Order> order = orderRepository.findById(orderId);
    return order.map(o -> new GetOrderResponse(o.getId(), ...))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
  }
}
```

**AFTER**:
```java
@RestController
@RequestMapping("/orders")
public class OrderController {

  @GetMapping("/{orderId}")
  public GetOrderResponse getOrder(@PathVariable String orderId) {  // String
    Optional<Order> order = orderRepository.findById(orderId);
    return order.map(o -> new GetOrderResponse(o.getId(), ...))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
  }
}
```

**Changes**:
- Path variable types: `long` → `String`
- URL pattern: `/orders/123` → `/orders/550e8400-e29b-41d4-a716-446655440000`

---

### 3.6 DTO/API Contract Changes

**BEFORE**:
```java
public class CreateOrderRequest {
  private long consumerId;
  private long restaurantId;
  // ...
}

public class CreateOrderResponse {
  private long orderId;
}
```

**AFTER**:
```java
public class CreateOrderRequest {
  private String consumerId;   // Changed
  private String restaurantId; // Changed
  // ...
}

public class CreateOrderResponse {
  private String orderId;  // Changed
}
```

**Impact**: Breaking API change - requires versioning or coordinated deployment.

---

### 3.7 Saga Changes

**BEFORE (CreateOrderSaga)**:
```java
public class CreateOrderSagaState {
  private Long orderId;
  private Long consumerId;
  private Long restaurantId;
  // ...
}
```

**AFTER**:
```java
public class CreateOrderSagaState {
  private String orderId;
  private String consumerId;
  private String restaurantId;
  // ...
}
```

**Impact**: Saga state serialization changes - requires migration or parallel run.

---

### 3.8 Event Schema Changes

**BEFORE**:
```java
public class OrderCreatedEvent implements OrderDomainEvent {
  private OrderDetails orderDetails;

  public static class OrderDetails {
    private long consumerId;
    private long restaurantId;
    private List<OrderLineItem> lineItems;
    // ...
  }
}
```

**AFTER**:
```java
public class OrderCreatedEvent implements OrderDomainEvent {
  private OrderDetails orderDetails;

  public static class OrderDetails {
    private String consumerId;     // Changed
    private String restaurantId;   // Changed
    private List<OrderLineItem> lineItems;
    // ...
  }
}
```

**Impact**: Event schema evolution - subscribers must handle both Long and String IDs during transition.

---

## 4. AI-Automatable Changes

### 4.1 High Confidence (95%+ Automation)

These changes can be automated with high accuracy using AI code transformation tools.

#### A1. Type Changes (String ID Migration)

**Scope**: Change all `Long id` to `String id` across entities, services, controllers.

**Pattern**:
```java
// Pattern to find
@Id
@GeneratedValue
private Long id;

// Replace with
@Id
@GeneratedValue(generator = "uuid2")
@GenericGenerator(name = "uuid2", strategy = "uuid2")
@Column(columnDefinition = "STRING(36)")
private String id;
```

**Files Affected** (~150 files):
- All entity classes (Order, Consumer, Ticket, Restaurant, etc.)
- All repository interfaces (change generic type)
- All service classes (parameter types, variable types)
- All controller classes (path variable types)
- All DTO/request/response classes
- All saga state classes
- All event classes

**AI Tool**: AST-based code transformer
- Parse Java AST
- Find `@Id` annotations
- Change field type `Long` → `String`
- Update all usages (getters, setters, constructors)
- Update method signatures
- Update comparisons (`==` → `.equals()`)

**Success Rate**: 95%
**Manual Review**: Yes (verify foreign key references)

---

#### A2. Add @Version Fields

**Scope**: Add optimistic locking to all JPA entities missing `@Version`.

**Pattern**:
```java
// Find entities without @Version
@Entity
public class Ticket {
  @Id
  private String id;
  // Missing: @Version
}

// Add @Version field
@Entity
public class Ticket {
  @Id
  private String id;

  @Version
  private Long version;  // ADD THIS
}
```

**Files Affected** (~20 entities):
- Consumer
- Ticket
- Restaurant
- Delivery
- (Order already has it ✓)

**AI Tool**: AST-based transformer
- Find `@Entity` classes
- Check for `@Version` annotation
- If missing, add `@Version private Long version;`

**Success Rate**: 98%
**Manual Review**: Minimal

---

#### A3. Add Timestamp Fields

**Scope**: Add `createdAt` timestamp to all entities for indexing.

**Pattern**:
```java
@Column(nullable = false, columnDefinition = "TIMESTAMP")
@CreationTimestamp
private Timestamp createdAt;
```

**Success Rate**: 95%

---

#### A4. Repository Interface Updates

**Scope**: Change repository generic types.

**Pattern**:
```java
// Before
public interface OrderRepository extends CrudRepository<Order, Long> {}

// After
public interface OrderRepository extends CrudRepository<Order, String> {}
```

**Success Rate**: 99%

---

#### A5. Primitive to Object Type Changes

**Scope**: Change `long id` parameters to `String id`.

**Pattern**:
```java
// Before
public Order findOrder(long orderId) { ... }

// After
public Order findOrder(String orderId) { ... }
```

**Success Rate**: 90% (need to verify call sites)

---

#### A6. Comparison Operator Changes

**Scope**: Change `==` to `.equals()` for ID comparisons.

**Pattern**:
```java
// Before
if (order.getId() == existingId) { ... }

// After
if (order.getId().equals(existingId)) { ... }
```

**Success Rate**: 85% (watch for null safety)

---

### 4.2 Medium Confidence (70-90% Automation)

These changes can be partially automated but require manual review.

#### B1. Query Method Signatures

**Scope**: Update Spring Data JPA query methods.

**Pattern**:
```java
// Before
Optional<Order> findByConsumerId(Long consumerId);

// After
Optional<Order> findByConsumerId(String consumerId);
```

**Success Rate**: 80%
**Manual Review**: Required (verify query logic)

---

#### B2. Test Data Updates

**Scope**: Update test fixtures with UUID generation.

**Pattern**:
```java
// Before
Order order = new Order();
order.setId(1L);

// After
Order order = new Order();
order.setId(UUID.randomUUID().toString());
```

**Success Rate**: 70%
**Manual Review**: Required (test data patterns vary)

---

#### B3. JSON Schema Updates

**Scope**: Update API schemas in *-api-spec modules.

**Pattern**:
```json
// Before
{
  "orderId": {
    "type": "integer",
    "format": "int64"
  }
}

// After
{
  "orderId": {
    "type": "string",
    "format": "uuid"
  }
}
```

**Success Rate**: 85%
**Manual Review**: Required (schema validation)

---

### 4.3 AI Tooling Recommendations

**Recommended Tools**:

1. **OpenRewrite** (https://docs.openrewrite.org/)
   - AST-based Java refactoring
   - Can create custom recipes for ID migration
   - High precision, low false positives

2. **Refaster** (Google)
   - Template-based refactoring
   - Good for repetitive transformations

3. **Custom LLM-based Tool**
   - Use Claude/GPT-4 with AST parsing
   - Generate transformation scripts
   - Batch process files

**Sample OpenRewrite Recipe**:
```yaml
type: specs.openrewrite.org/v1beta/recipe
name: com.ftgo.MigrateToSpanner
displayName: Migrate FTGO to Cloud Spanner
description: Converts MySQL schema to Spanner-compatible schema
recipeList:
  - org.openrewrite.java.ChangeType:
      oldFullyQualifiedTypeName: java.lang.Long
      newFullyQualifiedTypeName: java.lang.String
      matchIdFields: true
  - org.openrewrite.java.AddAnnotation:
      annotationType: javax.persistence.Version
      targetClass: javax.persistence.Entity
      condition: missingVersionField
```

---

## 5. Manual Changes Required

### 5.1 Critical Manual Changes (Require Human Judgment)

#### M1. Schema Design Decisions

**Task**: Design Spanner DDL with proper primary keys and indexes.

**Why Manual**: Requires understanding of:
- Query patterns
- Write volume
- Read volume
- Hotspot avoidance strategies
- Cost implications

**Example Decision**:
```sql
-- Option 1: UUID primary key + timestamp index
CREATE TABLE orders (
  id STRING(36) NOT NULL,
  created_timestamp TIMESTAMP NOT NULL,
  ...
) PRIMARY KEY (id);
CREATE INDEX idx_orders_created ON orders(created_timestamp DESC, id);

-- Option 2: Timestamp + UUID composite primary key (natural ordering)
CREATE TABLE orders (
  created_timestamp TIMESTAMP NOT NULL,
  id STRING(36) NOT NULL,
  ...
) PRIMARY KEY (created_timestamp DESC, id);

-- Option 3: Hash of timestamp + UUID (better distribution)
CREATE TABLE orders (
  id_hash STRING(64) NOT NULL,  -- SHA256(timestamp || uuid)[:8]
  id STRING(36) NOT NULL,
  created_timestamp TIMESTAMP NOT NULL,
  ...
) PRIMARY KEY (id_hash, id);
```

**Decision Factors**:
- Most common query: "Get recent orders" → Option 2
- Most common query: "Get order by ID" → Option 1
- High write volume with time-based queries → Option 3

**Impact**: HIGH - Wrong choice causes performance issues

---

#### M2. Interleaved Table Design

**Task**: Decide which child tables to interleave.

**Why Manual**: Requires analyzing:
- Parent-child access patterns
- Co-location benefits vs flexibility
- Future schema evolution

**Example**:
```sql
-- Should order_line_items be interleaved?

-- Option A: Interleaved (faster reads, harder to query independently)
CREATE TABLE order_line_items (
  order_id STRING(36) NOT NULL,
  line_item_id STRING(36) NOT NULL,
  ...
) PRIMARY KEY (order_id, line_item_id),
  INTERLEAVE IN PARENT orders ON DELETE CASCADE;

-- Option B: Separate (slower reads with order, flexible queries)
CREATE TABLE order_line_items (
  line_item_id STRING(36) NOT NULL,
  order_id STRING(36) NOT NULL,
  ...
) PRIMARY KEY (line_item_id);
CREATE INDEX idx_line_items_order ON order_line_items(order_id);
```

**Decision**: Interleave if:
- Always queried with parent ✓
- Never queried independently ✓
- Parent-child 1:N relationship ✓

**For FTGO**: INTERLEAVE order_line_items (always read with order)

---

#### M3. Index Design for Query Performance

**Task**: Create secondary indexes for common queries.

**Why Manual**: Requires analyzing:
- Query patterns from application logs
- Index cost (storage + write overhead)
- Read vs write ratio

**Example Indexes Needed**:
```sql
-- Query: Find orders by consumer
CREATE INDEX idx_orders_consumer
ON orders(consumer_id, created_timestamp DESC);

-- Query: Find orders by restaurant
CREATE INDEX idx_orders_restaurant
ON orders(restaurant_id, created_timestamp DESC);

-- Query: Find orders by state
CREATE INDEX idx_orders_state
ON orders(state, created_timestamp DESC);

-- Query: Find tickets by restaurant
CREATE INDEX idx_tickets_restaurant
ON tickets(restaurant_id, state);
```

**Decision Factors**:
- Query frequency (from metrics)
- Index selectivity
- Write amplification acceptable?

---

#### M4. Transaction Boundary Review

**Task**: Review all transactions for distributed transaction limits.

**Why Manual**: Spanner has different transaction semantics:
- Max 20,000 mutations per transaction
- Read-write transactions have higher latency
- Cross-region transactions expensive

**Example Review**:
```java
// Current: Single transaction
@Transactional
public void createOrderAndTicket(OrderRequest request) {
  Order order = orderRepository.save(new Order(...));
  Ticket ticket = ticketRepository.save(new Ticket(...));
  // ⚠️ Are Order and Ticket in same Spanner instance?
  // If distributed across databases, this is now a distributed transaction
}
```

**Decision**:
- Keep transaction if critical for consistency
- Or use saga pattern (already used in FTGO ✓)

**For FTGO**: Already uses sagas, minimal change needed ✓

---

#### M5. Pessimistic Lock Removal

**Task**: Identify and remove pessimistic locking patterns.

**Why Manual**: Requires understanding business logic and concurrency requirements.

**Search Pattern**:
```java
// Find these patterns:
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Lock(LockModeType.PESSIMISTIC_READ)

// Or explicit locking:
entityManager.lock(entity, LockModeType.PESSIMISTIC_WRITE);
```

**Current State in FTGO**:
- No explicit pessimistic locks found ✓
- All entities use optimistic locking with `@Version` (Order) or state machines (Ticket)

**Action Required**: Minimal (add @Version to entities missing it)

---

#### M6. Retry Logic for Optimistic Lock Failures

**Task**: Add retry logic for `OptimisticLockException`.

**Why Manual**: Business logic determines retry strategy.

**Example**:
```java
// BEFORE (implicit retry by JPA)
@Transactional
public void updateOrder(Long orderId, OrderUpdate update) {
  Order order = orderRepository.findById(orderId).orElseThrow();
  order.update(update);
  orderRepository.save(order);  // Throws OptimisticLockException if version mismatch
}

// AFTER (explicit retry with backoff)
public void updateOrder(String orderId, OrderUpdate update) {
  int maxRetries = 3;
  int attempt = 0;

  while (attempt < maxRetries) {
    try {
      updateOrderInternal(orderId, update);
      return;  // Success
    } catch (OptimisticLockException e) {
      attempt++;
      if (attempt >= maxRetries) {
        throw new OrderConcurrentUpdateException(orderId, e);
      }
      // Exponential backoff
      Thread.sleep((long) Math.pow(2, attempt) * 100);
    }
  }
}

@Transactional
private void updateOrderInternal(String orderId, OrderUpdate update) {
  Order order = orderRepository.findById(orderId).orElseThrow();
  order.update(update);
  orderRepository.save(order);
}
```

**Decision Factors**:
- Expected contention rate
- Acceptable retry attempts
- User experience (retry transparent or error?)

---

#### M7. Event Schema Migration Strategy

**Task**: Handle mixed Long/String IDs during migration.

**Why Manual**: Requires data migration strategy.

**Strategy Options**:

**Option A: Big Bang (Downtime)**
- Stop all services
- Migrate all data
- Deploy new code with String IDs
- Restart services

**Option B: Dual Writing (No Downtime)**
- Phase 1: Update events to include both `id` (Long) and `idV2` (String)
- Phase 2: Consumers read `idV2` if present, else `id`
- Phase 3: Remove `id` field
- Phase 4: Rename `idV2` → `id`

**Example Dual Write**:
```java
public class OrderCreatedEvent {
  @Deprecated
  private Long id;         // Old (for backward compatibility)

  private String idV2;     // New (UUID)

  public String getId() {
    return idV2 != null ? idV2 : String.valueOf(id);  // Graceful fallback
  }
}
```

**Decision**: Use Option B for zero-downtime migration

---

#### M8. Database Migration Execution

**Task**: Execute data migration from MySQL to Spanner.

**Why Manual**: Requires careful planning:
- Data volume estimation
- Migration time estimation
- Consistency validation
- Rollback strategy

**Tools**:
- Google Dataflow (for large datasets)
- HarbourBridge (MySQL → Spanner migration tool)
- Custom ETL scripts

**Steps**:
1. Schema creation in Spanner
2. Initial bulk load (read-only mode)
3. Incremental sync (CDC from MySQL)
4. Cutover (switch writes to Spanner)
5. Verify data consistency
6. Decommission MySQL

**Timeline**: 2-4 weeks per service

---

#### M9. Connection Pool Configuration

**Task**: Configure Spanner connection pooling.

**Why Manual**: Different from MySQL pooling.

**MySQL Config**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
```

**Spanner Config**:
```yaml
spring:
  cloud:
    gcp:
      spanner:
        instance-id: ftgo-instance
        database: ftgo_order_service
        num-rpc-channels: 4
        prefetch-chunks: 4
        min-sessions: 25
        max-sessions: 400
```

**Tuning Factors**:
- Sessions vs connections (Spanner uses sessions)
- Read-write vs read-only sessions
- Cost per session

---

#### M10. Cost Optimization Decisions

**Task**: Optimize for cost (nodes, storage, read/write operations).

**Why Manual**: Business decision on cost vs performance trade-offs.

**Cost Factors**:
- Node count (minimum 3 for production)
- Storage (per GB)
- Read operations
- Write operations

**Optimizations**:
- Use read-only transactions for queries (cheaper)
- Batch writes (reduce write operations)
- Use stale reads for non-critical data (much cheaper)

**Example**:
```java
// Standard read (uses read-write transaction)
@Transactional
public Order getOrder(String id) {
  return orderRepository.findById(id).orElseThrow();
}

// Optimized read (read-only transaction, can use stale data)
@Transactional(readOnly = true)
public Order getOrder(String id) {
  return orderRepository.findById(id).orElseThrow();
}

// Further optimized (stale read, up to 15 seconds old, much cheaper)
public Order getOrderStale(String id) {
  return spannerTemplate.readOnly(
    TimestampBound.ofMaxStaleness(15, TimeUnit.SECONDS)
  ).execute(txn -> {
    return orderRepository.findById(id).orElseThrow();
  });
}
```

**Decision**: Use stale reads for order history queries (acceptable staleness)

---

### 5.2 Manual Review Required (After AI Automation)

#### R1. Verify All ID Type Changes
- Review all `Long id` → `String id` changes
- Check for missed references
- Verify serialization/deserialization

#### R2. Verify Foreign Key References
- Ensure all FK references updated consistently
- Check for orphaned references

#### R3. Test Data Validation
- Verify UUID generation in tests
- Check for hardcoded IDs (1L, 2L, etc.)

#### R4. API Backward Compatibility
- Review breaking API changes
- Decide on versioning strategy

#### R5. Performance Testing
- Load test with Spanner
- Compare with MySQL baseline
- Identify hotspots

---

## 6. Query Structure Changes

### 6.1 Current Query Patterns

**Pattern 1: Simple Lookup (No Change)**
```java
// Current
Order order = orderRepository.findById(orderId).orElseThrow();

// Spanner (same code, different ID type)
Order order = orderRepository.findById(orderId).orElseThrow();  // orderId is now String
```

**Pattern 2: Range Queries (Needs Index)**
```java
// Current (uses timestamp index in MySQL)
List<Order> recentOrders = orderRepository.findByCreatedAtBetween(startTime, endTime);

// Spanner (needs explicit index)
// DDL:
CREATE INDEX idx_orders_created ON orders(created_timestamp DESC);

// Code: No change
List<Order> recentOrders = orderRepository.findByCreatedAtBetween(startTime, endTime);
```

**Pattern 3: Filtering by Foreign Key (Needs Index)**
```java
// Current
List<Order> orders = orderRepository.findByConsumerId(consumerId);

// Spanner (needs index for efficient lookup)
// DDL:
CREATE INDEX idx_orders_consumer ON orders(consumer_id);

// Code: No change
List<Order> orders = orderRepository.findByConsumerId(consumerId);
```

---

### 6.2 Query Anti-Patterns for Spanner

#### Anti-Pattern 1: Non-Indexed Scans

**Problem**:
```java
// Scans entire table (expensive in Spanner)
@Query("SELECT o FROM Order o WHERE o.state = :state")
List<Order> findByState(@Param("state") OrderState state);
```

**Solution**:
```sql
-- Add index
CREATE INDEX idx_orders_state ON orders(state);
```

---

#### Anti-Pattern 2: Offset-Based Pagination

**Problem**:
```java
// Uses OFFSET (scans and skips rows, expensive)
@Query("SELECT o FROM Order o ORDER BY o.createdAt DESC")
Page<Order> findAll(Pageable pageable);  // PageRequest.of(page, size)
```

**Solution (Cursor-Based Pagination)**:
```java
// Use cursor (seek method)
@Query("SELECT o FROM Order o WHERE o.createdAt < :cursor ORDER BY o.createdAt DESC LIMIT :limit")
List<Order> findPage(@Param("cursor") Timestamp cursor, @Param("limit") int limit);
```

---

#### Anti-Pattern 3: Unbounded Queries

**Problem**:
```java
// Returns all orders (millions of rows)
List<Order> findAll();
```

**Solution**:
```java
// Always use limits
@Query("SELECT o FROM Order o ORDER BY o.createdAt DESC LIMIT 1000")
List<Order> findRecent();
```

---

### 6.3 Optimized Query Patterns

#### Pattern 1: Denormalized Reads (Already Used in FTGO ✓)

**Good**:
```java
// Order has embedded DeliveryInformation
@Embedded
private DeliveryInformation deliveryInformation;

// Single query to get order with delivery info
Order order = orderRepository.findById(orderId).orElseThrow();
DeliveryInformation delivery = order.getDeliveryInformation();
```

**Avoid**:
```java
// Separate query for delivery info (if it were normalized)
Order order = orderRepository.findById(orderId).orElseThrow();
Delivery delivery = deliveryRepository.findByOrderId(orderId);  // Extra round trip
```

---

#### Pattern 2: Batch Reads

**Good**:
```java
// Fetch multiple orders in one query
List<Order> orders = orderRepository.findAllById(orderIds);
```

**Avoid**:
```java
// N+1 queries
for (String orderId : orderIds) {
  Order order = orderRepository.findById(orderId).orElseThrow();  // N queries!
}
```

---

#### Pattern 3: Read-Only Transactions for Queries

**Good**:
```java
@Transactional(readOnly = true)
public List<Order> getConsumerOrders(String consumerId) {
  return orderRepository.findByConsumerId(consumerId);
}
```

**Benefit**: Lower latency and cost in Spanner.

---

#### Pattern 4: Stale Reads for Non-Critical Data

**Use Case**: Order history (staleness acceptable)

```java
// Use Spanner stale read (up to 15 seconds old, much cheaper)
public List<Order> getOrderHistory(String consumerId) {
  return spannerTemplate.readOnly(
    TimestampBound.ofMaxStaleness(15, TimeUnit.SECONDS)
  ).execute(txn -> {
    return orderRepository.findByConsumerId(consumerId);
  });
}
```

---

## 7. Locking Strategy Migration

### 7.1 Current Locking Analysis

**Good News**: FTGO already uses optimistic locking patterns! ✓

**Order Service**:
```java
@Entity
public class Order {
  @Id
  private Long id;

  @Version
  private Long version;  // ✓ Optimistic locking already implemented
}
```

**Ticket Service** (State Machine):
```java
@Entity
public class Ticket {
  @Enumerated(EnumType.STRING)
  private TicketState state;  // State machine prevents conflicts

  public List<TicketDomainEvent> accept(LocalDateTime readyBy) {
    switch (state) {
      case AWAITING_ACCEPTANCE:
        state = TicketState.ACCEPTED;  // ✓ State transition guarded
        return singletonList(new TicketAcceptedEvent(readyBy));
      default:
        throw new UnsupportedStateTransitionException(state);
    }
  }
}
```

**No Pessimistic Locks Found**: ✓
```bash
# Search results:
grep -r "@Lock\|PESSIMISTIC" --include="*.java" .
# No results found
```

---

### 7.2 Optimistic Locking Migration Checklist

#### Step 1: Add @Version to All Entities

**Entities Missing @Version**:
1. Consumer ❌
2. Ticket ❌
3. Restaurant ❌
4. Delivery ❌

**Action**:
```java
// Add to each entity
@Version
private Long version;
```

---

#### Step 2: Configure JPA for Optimistic Locking

**application.yml**:
```yaml
spring:
  jpa:
    properties:
      hibernate:
        # Fail fast on version mismatch
        jdbc:
          batch_versioned_data: true
        # Log version conflicts
        show_sql: true
```

---

#### Step 3: Handle OptimisticLockException

**Pattern**:
```java
@Service
public class OrderService {

  private static final int MAX_RETRIES = 3;

  public void updateOrder(String orderId, OrderUpdate update) {
    RetryTemplate.builder()
      .maxAttempts(MAX_RETRIES)
      .exponentialBackoff(100, 2, 1000)
      .retryOn(OptimisticLockException.class)
      .build()
      .execute(ctx -> {
        return updateOrderInternal(orderId, update);
      });
  }

  @Transactional
  private Order updateOrderInternal(String orderId, OrderUpdate update) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.applyUpdate(update);
    return orderRepository.save(order);
  }
}
```

---

#### Step 4: Monitoring Optimistic Lock Failures

**Metrics to Track**:
- `OptimisticLockException` count
- Retry success rate
- Average retries per update

**Alert Thresholds**:
- > 5% lock failure rate → High contention, investigate
- > 3 average retries → Consider redesign (e.g., eventual consistency)

---

### 7.3 Spanner-Specific Locking Patterns

#### Pattern 1: Explicit Read-Write Transaction

**Use Case**: Update order state based on current state.

```java
@Transactional
public void approveOrder(String orderId) {
  Order order = orderRepository.findById(orderId).orElseThrow();

  // Spanner automatically locks the row for this transaction
  if (order.getState() != OrderState.APPROVAL_PENDING) {
    throw new InvalidStateTransitionException();
  }

  order.approve();
  orderRepository.save(order);  // Version check happens here
}
```

**Spanner Behavior**:
- Read locks row implicitly in read-write transaction
- Write checks version on commit
- Throws `OptimisticLockException` if version mismatch

---

#### Pattern 2: Read-Modify-Write with Retry

```java
public void incrementCounter(String entityId) {
  // Spanner: Read-modify-write with automatic retry
  transactionTemplate.execute(status -> {
    MyEntity entity = repository.findById(entityId).orElseThrow();
    entity.incrementCounter();
    repository.save(entity);
    return null;
  });
}
```

---

#### Pattern 3: Avoid Write Skew with Versioning

**Problem**: Write skew (two transactions read same data, write different updates).

**Solution**: Use @Version to detect conflicts.

```java
// Transaction 1: Approve order
@Transactional
public void approveOrder(String orderId) {
  Order order = orderRepository.findById(orderId).orElseThrow();
  order.approve();  // Sets state = APPROVED
  orderRepository.save(order);  // version++
}

// Transaction 2: Cancel order (runs concurrently)
@Transactional
public void cancelOrder(String orderId) {
  Order order = orderRepository.findById(orderId).orElseThrow();
  order.cancel();  // Sets state = CANCELLED
  orderRepository.save(order);  // Throws OptimisticLockException (version mismatch)
}
```

**Result**: One transaction succeeds, other retries with fresh data.

---

## 8. Service-by-Service Migration Plan

### 8.1 Migration Sequence (Recommended Order)

**Phase 1: Non-Critical Services** (Learn and validate)
1. ftgo-order-history-service (already NoSQL, easy to migrate)
2. ftgo-restaurant-service (master data, low write volume)

**Phase 2: Saga Participants**
3. ftgo-consumer-service (simple entity, low complexity)
4. ftgo-delivery-service (post-order, less critical path)
5. ftgo-kitchen-service (critical but well-structured)

**Phase 3: Critical Orchestrator**
6. ftgo-order-service (last, requires all participants migrated)

**Phase 4: Event Sourced Service**
7. ftgo-accounting-service (special handling for event store)

---

### 8.2 Per-Service Migration Checklist

#### Service: ftgo-order-service

**Complexity**: HIGH
**Entities**: Order (with @Version ✓), Restaurant (replica)
**Dependencies**: All other services

**Code Changes**:
| Change Type | Files Affected | AI Automatable | Manual Review |
|-------------|----------------|----------------|---------------|
| Entity ID type (Long → String) | 2 entities | ✓ | Required |
| Service layer parameter types | ~15 methods | ✓ | Required |
| Controller parameter types | ~8 endpoints | ✓ | Required |
| Saga state objects | 3 sagas | ✓ | Required |
| Repository interfaces | 2 repos | ✓ | Minimal |
| Event schema | 8 event classes | ✓ | Required |
| Test data | ~30 test classes | Partial | Required |

**Schema Changes**:
```sql
-- Orders table
CREATE TABLE orders (
  id STRING(36) NOT NULL,
  version INT64 NOT NULL,
  state STRING(50) NOT NULL,
  consumer_id STRING(36) NOT NULL,
  restaurant_id STRING(36) NOT NULL,
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  ...
) PRIMARY KEY (id);

CREATE INDEX idx_orders_created ON orders(created_timestamp DESC);
CREATE INDEX idx_orders_consumer ON orders(consumer_id, created_timestamp DESC);
CREATE INDEX idx_orders_restaurant ON orders(restaurant_id, created_timestamp DESC);
CREATE INDEX idx_orders_state ON orders(state, created_timestamp DESC);

-- Order line items (interleaved)
CREATE TABLE order_line_items (
  order_id STRING(36) NOT NULL,
  line_item_id STRING(36) NOT NULL,
  menu_item_id STRING(255),
  name STRING(255),
  price INT64,
  quantity INT64,
) PRIMARY KEY (order_id, line_item_id),
  INTERLEAVE IN PARENT orders ON DELETE CASCADE;
```

**Query Changes**:
- No custom queries (uses Spring Data) ✓
- Add indexes for findByConsumerId, findByRestaurantId

**Testing**:
- Integration tests with Spanner emulator
- Saga tests (all 3 sagas)
- Load testing (critical path)

**Estimated Effort**: 3-4 weeks

---

#### Service: ftgo-consumer-service

**Complexity**: LOW
**Entities**: Consumer (needs @Version ❌)
**Dependencies**: None (saga participant)

**Code Changes**:
| Change Type | Files Affected | AI Automatable | Manual Review |
|-------------|----------------|----------------|---------------|
| Add @Version field | 1 entity | ✓ | Minimal |
| Entity ID type | 1 entity | ✓ | Minimal |
| Service layer | ~5 methods | ✓ | Minimal |
| Controller | ~3 endpoints | ✓ | Minimal |
| Repository | 1 repo | ✓ | Minimal |
| Event schema | 1 event class | ✓ | Minimal |
| Test data | ~10 test classes | Partial | Minimal |

**Schema Changes**:
```sql
CREATE TABLE consumers (
  id STRING(36) NOT NULL,
  version INT64 NOT NULL,
  first_name STRING(255),
  last_name STRING(255),
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
) PRIMARY KEY (id);

CREATE INDEX idx_consumers_created ON consumers(created_timestamp DESC);
```

**Estimated Effort**: 1 week

---

#### Service: ftgo-kitchen-service

**Complexity**: MEDIUM
**Entities**: Ticket (needs @Version ❌), Restaurant (replica)
**Dependencies**: Restaurant service events

**Code Changes**:
| Change Type | Files Affected | AI Automatable | Manual Review |
|-------------|----------------|----------------|---------------|
| Add @Version field | 1 entity | ✓ | Minimal |
| Entity ID type | 2 entities | ✓ | Required |
| Service layer | ~12 methods | ✓ | Required |
| Controller | ~5 endpoints | ✓ | Minimal |
| Repository | 2 repos | ✓ | Minimal |
| Event schema | 5 event classes | ✓ | Required |
| Test data | ~20 test classes | Partial | Required |

**Schema Changes**:
```sql
CREATE TABLE tickets (
  id STRING(36) NOT NULL,  -- From Order ID
  version INT64 NOT NULL,
  state STRING(50) NOT NULL,
  restaurant_id STRING(36) NOT NULL,
  ready_by TIMESTAMP,
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  ...
) PRIMARY KEY (id);

CREATE INDEX idx_tickets_restaurant_state ON tickets(restaurant_id, state);

-- Ticket line items (interleaved)
CREATE TABLE ticket_line_items (
  ticket_id STRING(36) NOT NULL,
  line_item_id STRING(36) NOT NULL,
  menu_item_id STRING(255),
  quantity INT64,
) PRIMARY KEY (ticket_id, line_item_id),
  INTERLEAVE IN PARENT tickets ON DELETE CASCADE;
```

**Estimated Effort**: 2 weeks

---

#### Service: ftgo-restaurant-service

**Complexity**: LOW-MEDIUM
**Entities**: Restaurant (needs @Version ❌), MenuItem
**Dependencies**: None (master data)

**Code Changes**: Similar to consumer-service

**Schema Changes**:
```sql
CREATE TABLE restaurants (
  id STRING(36) NOT NULL,
  version INT64 NOT NULL,
  name STRING(255) NOT NULL,
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  ...
) PRIMARY KEY (id);

CREATE INDEX idx_restaurants_name ON restaurants(name);

-- Menu items (interleaved)
CREATE TABLE menu_items (
  restaurant_id STRING(36) NOT NULL,
  menu_item_id STRING(36) NOT NULL,
  name STRING(255),
  price INT64,
) PRIMARY KEY (restaurant_id, menu_item_id),
  INTERLEAVE IN PARENT restaurants ON DELETE CASCADE;
```

**Estimated Effort**: 1-2 weeks

---

#### Service: ftgo-accounting-service

**Complexity**: HIGH (Event Sourcing)
**Entities**: Account (event-sourced)
**Dependencies**: Eventuate event store

**Special Considerations**:
- Event store schema different from regular JPA
- Need to migrate event store to Spanner
- Account IDs change (Long → String)

**Code Changes**:
- Account aggregate: Change ID type
- Event payloads: Change accountId fields
- Event store schema: Custom Spanner schema

**Schema Changes**:
```sql
-- Eventuate event store tables
CREATE TABLE events (
  event_id STRING(1000) NOT NULL,
  event_type STRING(1000),
  event_data STRING(MAX),
  entity_type STRING(1000) NOT NULL,
  entity_id STRING(1000) NOT NULL,  -- Account ID (UUID)
  triggering_event STRING(MAX),
  metadata STRING(1000),
  published INT64 NOT NULL DEFAULT 0,
  creation_time INT64 NOT NULL,
) PRIMARY KEY (entity_type, entity_id, event_id);

CREATE INDEX events_idx ON events(entity_type, entity_id, event_id);
CREATE INDEX events_published_idx ON events(published, event_id);
```

**Estimated Effort**: 2-3 weeks

---

#### Service: ftgo-delivery-service

**Complexity**: MEDIUM
**Entities**: Delivery, Restaurant (replica)
**Dependencies**: Kitchen, Order, Restaurant events

**Estimated Effort**: 2 weeks

---

#### Service: ftgo-order-history-service

**Complexity**: LOW (Already DynamoDB)
**Migration**: DynamoDB → Spanner (optional, can keep DynamoDB)

**If Migrating**:
```sql
CREATE TABLE order_history (
  consumer_id STRING(36) NOT NULL,
  order_id STRING(36) NOT NULL,
  state STRING(50),
  order_total INT64,
  created_timestamp TIMESTAMP NOT NULL,
  ...
) PRIMARY KEY (consumer_id, created_timestamp DESC, order_id);
```

**Estimated Effort**: 1 week

---

## 9. Testing Strategy

### 9.1 Unit Tests

**Changes Required**:
- Update test data (UUID generation)
- Update assertions (String comparisons)
- Mock repository returns (String IDs)

**Example**:
```java
// Before
@Test
public void testCreateOrder() {
  Order order = new Order();
  order.setId(1L);
  when(orderRepository.save(any())).thenReturn(order);

  Order result = orderService.createOrder(...);
  assertEquals(1L, result.getId());
}

// After
@Test
public void testCreateOrder() {
  String orderId = UUID.randomUUID().toString();
  Order order = new Order();
  order.setId(orderId);
  when(orderRepository.save(any())).thenReturn(order);

  Order result = orderService.createOrder(...);
  assertEquals(orderId, result.getId());
}
```

**Automation**: 70% automatable (test data patterns vary)

---

### 9.2 Integration Tests

**Setup**:
- Use Spanner emulator for local testing
- Configure test containers

**Configuration**:
```yaml
# test/application.yml
spring:
  cloud:
    gcp:
      spanner:
        emulator-host: localhost:9010
        project-id: test-project
        instance-id: test-instance
        database: test-database
```

**Test Pattern**:
```java
@SpringBootTest
@AutoConfigureSpannerEmulator
public class OrderServiceIntegrationTest {

  @Autowired
  private OrderRepository orderRepository;

  @Test
  public void testSaveAndRetrieveOrder() {
    Order order = new Order();
    order.setId(UUID.randomUUID().toString());
    order.setState(OrderState.APPROVAL_PENDING);

    orderRepository.save(order);

    Optional<Order> retrieved = orderRepository.findById(order.getId());
    assertTrue(retrieved.isPresent());
    assertEquals(OrderState.APPROVAL_PENDING, retrieved.get().getState());
  }
}
```

---

### 9.3 Optimistic Lock Tests

**Test Concurrent Updates**:
```java
@Test
public void testOptimisticLockException() {
  String orderId = createOrder();

  // Simulate concurrent updates
  ExecutorService executor = Executors.newFixedThreadPool(2);

  Future<Void> update1 = executor.submit(() -> {
    orderService.updateOrder(orderId, new OrderUpdate("Update 1"));
    return null;
  });

  Future<Void> update2 = executor.submit(() -> {
    orderService.updateOrder(orderId, new OrderUpdate("Update 2"));
    return null;
  });

  // One should succeed, one should retry or fail
  try {
    update1.get();
    update2.get();
  } catch (ExecutionException e) {
    assertTrue(e.getCause() instanceof OptimisticLockException
            || e.getCause() instanceof OrderConcurrentUpdateException);
  }
}
```

---

### 9.4 Performance Tests

**Baseline Metrics (MySQL)**:
- Capture current latency and throughput
- Document query patterns

**Spanner Comparison**:
- Run same tests against Spanner
- Compare:
  - Read latency (p50, p95, p99)
  - Write latency
  - Throughput (QPS)
  - Cost per operation

**Load Test**:
```java
@Test
public void loadTest() {
  int numOrders = 10000;
  int concurrentUsers = 100;

  // Create orders concurrently
  ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);

  long startTime = System.currentTimeMillis();

  List<Future<Order>> futures = IntStream.range(0, numOrders)
    .mapToObj(i -> executor.submit(() -> orderService.createOrder(...)))
    .collect(Collectors.toList());

  // Wait for all to complete
  futures.forEach(f -> f.get());

  long duration = System.currentTimeMillis() - startTime;
  double qps = numOrders / (duration / 1000.0);

  System.out.println("QPS: " + qps);
  assertTrue(qps > 100);  // Threshold
}
```

---

### 9.5 Saga Tests

**Test All Saga Flows**:
1. CreateOrderSaga (happy path)
2. CreateOrderSaga (consumer validation fails → rollback)
3. CreateOrderSaga (payment fails → rollback)
4. CancelOrderSaga
5. ReviseOrderSaga

**Example**:
```java
@Test
public void testCreateOrderSagaSuccess() {
  // Arrange
  String consumerId = createConsumer();
  String restaurantId = createRestaurant();

  CreateOrderRequest request = new CreateOrderRequest(
    consumerId, restaurantId, lineItems
  );

  // Act
  String orderId = orderService.createOrder(request);

  // Wait for saga completion
  await().atMost(10, SECONDS).until(() -> {
    Order order = orderRepository.findById(orderId).orElseThrow();
    return order.getState() == OrderState.APPROVED;
  });

  // Assert
  Order order = orderRepository.findById(orderId).orElseThrow();
  assertEquals(OrderState.APPROVED, order.getState());

  // Verify ticket created
  Ticket ticket = ticketRepository.findById(orderId).orElseThrow();
  assertEquals(TicketState.ACCEPTED, ticket.getState());
}
```

---

### 9.6 Data Migration Validation

**Validate Migrated Data**:
```sql
-- Compare counts
SELECT 'MySQL' as source, COUNT(*) as count FROM mysql.orders
UNION ALL
SELECT 'Spanner' as source, COUNT(*) as count FROM spanner.orders;

-- Compare checksums (sample)
SELECT
  id,
  MD5(CONCAT(id, state, consumer_id, restaurant_id)) as checksum
FROM mysql.orders
ORDER BY id
LIMIT 1000;

-- Compare with Spanner
SELECT
  id,
  MD5(CONCAT(id, state, consumer_id, restaurant_id)) as checksum
FROM spanner.orders
ORDER BY id
LIMIT 1000;
```

---

## 10. Rollback Plan

### 10.1 Rollback Triggers

**When to Rollback**:
- Critical bugs in Spanner integration
- Performance regression > 50%
- Data inconsistencies detected
- Cost exceeds budget by > 100%

### 10.2 Rollback Procedure

**Phase 1: Immediate Rollback** (< 1 hour)
1. Stop writes to Spanner
2. Route all traffic back to MySQL
3. Deploy previous version of code (Long IDs)

**Phase 2: Data Validation** (1-4 hours)
1. Compare MySQL and Spanner data
2. Identify inconsistencies
3. Sync Spanner → MySQL (if needed)

**Phase 3: Cleanup** (1-2 days)
1. Keep Spanner running in read-only mode
2. Analyze issues
3. Plan fix

### 10.3 Dual-Write Strategy (For Safe Migration)

**Write to Both Databases**:
```java
@Service
public class OrderService {

  @Autowired
  private OrderRepository mysqlRepo;  // MySQL

  @Autowired
  private OrderRepository spannerRepo;  // Spanner

  @Value("${migration.primary-db}")
  private String primaryDb;  // "mysql" or "spanner"

  public Order createOrder(CreateOrderRequest request) {
    Order order = new Order(...);

    // Write to both
    Order mysqlOrder = mysqlRepo.save(order);
    Order spannerOrder = spannerRepo.save(order);

    // Return from primary
    return primaryDb.equals("spanner") ? spannerOrder : mysqlOrder;
  }
}
```

**Benefits**:
- Zero-downtime cutover
- Easy rollback (just change primary)
- Data validation (compare both)

---

## 11. Summary & Recommendations

### 11.1 Migration Summary

| Aspect | Complexity | AI Automation | Manual Effort | Risk |
|--------|-----------|---------------|---------------|------|
| **Entity ID Changes** | Medium | 90% | 10% | Medium |
| **Add @Version Fields** | Low | 95% | 5% | Low |
| **Schema Design** | High | 0% | 100% | High |
| **Query Optimization** | Medium | 30% | 70% | Medium |
| **Locking Migration** | Low | 80% | 20% | Low |
| **Saga Changes** | Medium | 70% | 30% | Medium |
| **Event Schema** | Medium | 60% | 40% | High |
| **Data Migration** | High | 50% | 50% | High |
| **Testing** | Medium | 40% | 60% | Medium |

**Overall**:
- **AI-Automatable**: 60-70%
- **Manual Required**: 30-40%
- **Timeline**: 3-6 months (all services)
- **Risk**: MEDIUM-HIGH

---

### 11.2 Recommended Approach

**Phase 1: Preparation** (2-4 weeks)
1. ✓ Set up Spanner instances (dev, staging, prod)
2. ✓ Design schemas for all services
3. ✓ Create AI automation scripts (OpenRewrite recipes)
4. ✓ Set up Spanner emulator for local dev

**Phase 2: Pilot Migration** (2-3 weeks)
1. ✓ Migrate ftgo-restaurant-service (low risk)
2. ✓ Validate automation tools
3. ✓ Test rollback procedure
4. ✓ Gather lessons learned

**Phase 3: Incremental Migration** (8-12 weeks)
1. ✓ Migrate remaining services (one per week)
2. ✓ Dual-write for each service during cutover
3. ✓ Monitor performance and cost
4. ✓ Validate data consistency

**Phase 4: Cleanup** (2-4 weeks)
1. ✓ Decommission MySQL instances
2. ✓ Remove dual-write code
3. ✓ Optimize Spanner configuration
4. ✓ Documentation

---

### 11.3 Success Criteria

**Technical**:
- ✓ All services running on Spanner
- ✓ Zero data loss
- ✓ < 10% performance regression
- ✓ Optimistic locking working correctly
- ✓ All tests passing

**Operational**:
- ✓ Cost within 120% of MySQL cost
- ✓ Rollback plan tested and working
- ✓ Team trained on Spanner operations
- ✓ Monitoring dashboards deployed

**Business**:
- ✓ Zero downtime for users
- ✓ No customer-facing errors
- ✓ Improved scalability demonstrated

---

## Appendix A: Code Templates

### Template 1: UUID Primary Key Entity

```java
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.CreationTimestamp;

import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table(name = "orders")
public class Order {

  @Id
  @GeneratedValue(generator = "uuid2")
  @GenericGenerator(name = "uuid2", strategy = "uuid2")
  @Column(columnDefinition = "STRING(36)", nullable = false)
  private String id;

  @Version
  @Column(nullable = false)
  private Long version;

  @Column(nullable = false, columnDefinition = "TIMESTAMP")
  @CreationTimestamp
  private Timestamp createdAt;

  // Rest of fields...
}
```

---

### Template 2: Retry Logic for Optimistic Locks

```java
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@Service
public class OrderService {

  @Retryable(
    value = ObjectOptimisticLockingFailureException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 100, multiplier = 2)
  )
  @Transactional
  public void updateOrder(String orderId, OrderUpdate update) {
    Order order = orderRepository.findById(orderId)
      .orElseThrow(() -> new OrderNotFoundException(orderId));

    order.applyUpdate(update);
    orderRepository.save(order);
  }
}
```

---

### Template 3: Spanner DDL

```sql
-- Main table with UUID primary key
CREATE TABLE orders (
  id STRING(36) NOT NULL,
  version INT64 NOT NULL,
  state STRING(50) NOT NULL,
  consumer_id STRING(36) NOT NULL,
  restaurant_id STRING(36) NOT NULL,
  created_timestamp TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
  updated_timestamp TIMESTAMP OPTIONS (allow_commit_timestamp=true),
) PRIMARY KEY (id);

-- Indexes for common queries
CREATE INDEX idx_orders_created
ON orders(created_timestamp DESC);

CREATE INDEX idx_orders_consumer
ON orders(consumer_id, created_timestamp DESC);

CREATE INDEX idx_orders_state
ON orders(state, created_timestamp DESC);

-- Child table (interleaved)
CREATE TABLE order_line_items (
  order_id STRING(36) NOT NULL,
  line_item_id STRING(36) NOT NULL,
  menu_item_id STRING(255),
  name STRING(255),
  price INT64,
  quantity INT64,
) PRIMARY KEY (order_id, line_item_id),
  INTERLEAVE IN PARENT orders ON DELETE CASCADE;
```

---

