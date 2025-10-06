# FTGO to Google Cloud Spanner - Application Migration Summary

## Executive Overview

**Focus**: Application-level code changes required for Spanner compatibility
**Overall Complexity**: **MEDIUM-HIGH**
**Automation Potential**: **60-70% of code changes**

---

## Change Complexity Classification

### 🔴 High Complexity Changes (30%)

#### 1. Event Schema Evolution
**What**: Update event payloads to use String IDs instead of Long IDs
**Why Complex**:
- Events are contracts between services
- Must support backward compatibility during transition
- Affects all event publishers and subscribers
- Requires dual-field strategy (both Long and String during migration)

**Automation**: **60%** - Code changes automatable, backward compatibility strategy requires manual design
**Manual Review**: Required for migration choreography and consumer coordination

---

#### 2. Saga State Object Changes
**What**: Update all saga orchestration state objects (CreateOrderSaga, CancelOrderSaga, ReviseOrderSaga)
**Why Complex**:
- Saga state is serialized and persisted
- Changes affect distributed transaction flows
- Compensation logic must handle both old and new ID formats

**Automation**: **70%** - Type changes automatable, saga flow testing is manual
**Manual Review**: Required for saga orchestration testing and compensation verification

---

#### 3. Accounting Service (Event Sourcing)
**What**: Update event-sourced Account aggregate
**Why Complex**:
- Event store has special schema requirements for Spanner
- Event payloads contain account IDs that must change
- Event replay must handle mixed ID formats

**Automation**: **50%** - Aggregate code partially automatable, event store migration complex
**Manual Review**: Required for event store schema design and event migration strategy

---

#### 4. Transaction Boundary Review
**What**: Review all @Transactional methods for Spanner compatibility
**Why Complex**:
- Spanner has different transaction limits (20,000 mutations max)
- Distributed transactions have higher latency
- Some transaction patterns may need refactoring

**Automation**: **0%** - Requires understanding business logic and concurrency requirements
**Manual Review**: Required for every transactional method

---

### 🟡 Medium Complexity Changes (40%)

#### 5. Primary Key Type Migration (Long → String UUID)
**What**: Change all entity ID fields from Long to String across application layers
**Scope**:
- Entity classes (Order, Consumer, Ticket, Restaurant, etc.)
- Service layer methods (parameters and return types)
- Controller endpoints (path variables)
- DTOs and API contracts
- Repository interfaces

**Why Complex**: Touches every layer of every service (~150 files)

**Automation**: **90%** - AST-based tools can reliably handle type changes
**Manual Review**: Required to verify foreign key references and serialization logic

---

#### 6. Optimistic Locking Implementation
**What**: Add @Version fields to entities currently missing them
**Entities Affected**:
- Consumer ❌ (needs @Version)
- Ticket ❌ (needs @Version)
- Restaurant ❌ (needs @Version)
- Delivery ❌ (needs @Version)
- Order ✓ (already has @Version)

**Why Complex**: Must add retry logic for OptimisticLockException handling

**Automation**: **95%** - Adding @Version field is simple
**Manual Review**: Required for retry logic implementation and monitoring setup

---

#### 7. Query Pattern Adjustments
**What**: Update query patterns that don't work well in Spanner
**Changes Needed**:
- Convert offset-based pagination to cursor-based
- Add explicit indexes for common filters
- Remove unbounded queries (add LIMIT clauses)
- Change to read-only transactions where applicable

**Automation**: **30%** - Can detect anti-patterns, but fixes are context-specific
**Manual Review**: Required for query optimization and index design

---

#### 8. API Contract Breaking Changes
**What**: Update all API contracts (request/response DTOs)
**Impact**:
- All ID fields change from Long to String
- URLs change: `/orders/123` → `/orders/550e8400-e29b-41d4-a716-446655440000`
- JSON payloads change: `{"orderId": 123}` → `{"orderId": "550e8400-..."}`

**Why Complex**: Breaking changes require API versioning or coordinated deployment

**Automation**: **90%** - DTO field changes automatable
**Manual Review**: Required for versioning strategy and client coordination

---

### 🟢 Low Complexity Changes (30%)

#### 9. Repository Interface Updates
**What**: Change CrudRepository generic types from `<Entity, Long>` to `<Entity, String>`
**Scope**: ~10 repository interfaces

**Automation**: **99%** - Simple find-and-replace pattern
**Manual Review**: Minimal

---

#### 10. Add Timestamp Fields
**What**: Add createdAt timestamp to all entities for indexing support
**Why Needed**: Spanner best practice for range queries and natural ordering

**Automation**: **95%** - Template-based field addition
**Manual Review**: Minimal

---

#### 11. Comparison Operator Changes
**What**: Change equality checks from `==` to `.equals()` for String IDs
**Example**: `if (order.getId() == existingId)` → `if (order.getId().equals(existingId))`

**Automation**: **85%** - Pattern detection reliable
**Manual Review**: Required for null-safety verification

---

#### 12. Test Data Updates
**What**: Update test fixtures to use UUID generation instead of hardcoded Long IDs
**Example**: `order.setId(1L)` → `order.setId(UUID.randomUUID().toString())`

**Automation**: **70%** - UUID generation automatable, test logic may vary
**Manual Review**: Required for test assertion updates

---

## Automation Confidence Levels

### ✅ 95-100% Confidence (Safe to Automate)

| Change Type | Files Affected | Why High Confidence |
|-------------|----------------|---------------------|
| **Add @Version fields** | ~4 entities | Simple field addition, well-defined pattern |
| **Repository interface updates** | ~10 repositories | Generic type change, compile-time verified |
| **Add timestamp fields** | ~15 entities | Template-based, consistent pattern |

**Recommendation**: Automate fully, minimal spot-checking needed

---

### ⚠️ 80-94% Confidence (Automate with Review)

| Change Type | Files Affected | Why Review Needed |
|-------------|----------------|-------------------|
| **Entity ID type changes** | ~15 entities | Need to verify embedded objects, collections |
| **Service parameter types** | ~60 methods | May have business logic dependencies |
| **Controller parameter types** | ~25 endpoints | API compatibility concerns |
| **DTO/API contract updates** | ~30 classes | Serialization/deserialization verification |
| **Comparison operator changes** | ~100 occurrences | Null-safety edge cases |

**Recommendation**: Automate, then thorough code review

---

### ⚠️⚠️ 60-79% Confidence (Partial Automation)

| Change Type | Files Affected | Why Risky |
|-------------|----------------|-----------|
| **Saga state objects** | 3 sagas | Serialization format, state machine logic |
| **Event schema updates** | ~15 event classes | Backward compatibility requirements |
| **Test data updates** | ~80 test classes | Varied test patterns, assertion logic |
| **Query method signatures** | ~20 methods | Custom query logic varies |

**Recommendation**: Automate basic changes, manual refinement required

---

### 🚫 0-59% Confidence (Mostly Manual)

| Change Type | Why Manual Required |
|-------------|---------------------|
| **Transaction boundary review** | Requires business logic understanding |
| **Query optimization** | Context-specific, performance tuning |
| **Event migration strategy** | Backward compatibility design |
| **Retry logic implementation** | Concurrency and error handling logic |

**Recommendation**: Manual implementation with expert guidance

---

## What Requires Manual Review

### Category 1: Design Decisions (Cannot Automate)

**1. Event Schema Backward Compatibility Strategy**
- **Decision**: Dual-field approach vs versioned events
- **Who Decides**: Service architects + team leads
- **Impact**: All event publishers and consumers

**2. API Versioning Strategy**
- **Decision**: v1/v2 endpoints vs coordinated deployment
- **Who Decides**: API team + product
- **Impact**: Client applications

**3. Transaction Refactoring Strategy**
- **Decision**: Which transactions to keep vs split vs convert to sagas
- **Who Decides**: Service owners + architects
- **Impact**: Data consistency guarantees

**4. Retry Logic Configuration**
- **Decision**: Max retries, backoff strategy, failure handling
- **Who Decides**: Service owners
- **Impact**: User experience under contention

---

### Category 2: Code Review After Automation

**1. Verify ID Type Changes Completeness**
- **Check**: All Long → String conversions consistent
- **Look For**: Missed references, casting issues, serialization problems
- **Risk if Skipped**: Runtime type errors, data corruption

**2. Verify Foreign Key Consistency**
- **Check**: All FK references updated (consumerId, restaurantId, orderId)
- **Look For**: Type mismatches between related entities
- **Risk if Skipped**: Join failures, referential integrity issues

**3. Review API Breaking Changes**
- **Check**: All DTOs updated, backward compatibility handled
- **Look For**: Missing fields, wrong types, serialization issues
- **Risk if Skipped**: Client application failures

**4. Test Saga Flows**
- **Check**: All saga participants handle new ID types
- **Test**: Happy path + all compensation paths
- **Risk if Skipped**: Distributed transaction failures

**5. Validate Event Serialization**
- **Check**: Events serialize/deserialize correctly with new types
- **Test**: Kafka message compatibility across versions
- **Risk if Skipped**: Message processing failures, consumer errors

**6. Null-Safety Review**
- **Check**: String ID comparisons handle null correctly
- **Look For**: NPE risks from .equals() calls
- **Risk if Skipped**: NullPointerExceptions at runtime

---

### Category 3: Testing Requirements

**1. Integration Testing**
- **What**: All services with new ID types
- **Cannot Automate**: Business logic verification, state machine flows

**2. Optimistic Lock Concurrency Testing**
- **What**: Concurrent updates to same entity
- **Cannot Automate**: Contention scenarios, retry verification

**3. Saga Testing**
- **What**: All saga flows (happy + compensation paths)
- **Cannot Automate**: Distributed transaction verification

**4. API Compatibility Testing**
- **What**: Client compatibility with new ID format
- **Cannot Automate**: Integration with external systems

**5. Event Consumer Testing**
- **What**: All event subscribers handle new event format
- **Cannot Automate**: Cross-service message flow verification

---

## Service-by-Service Change Summary

| Service | Complexity | Key Changes | Automation % |
|---------|-----------|-------------|--------------|
| **ftgo-consumer-service** | 🟢 LOW | Add @Version, ID type change | 75% |
| **ftgo-restaurant-service** | 🟢 LOW-MEDIUM | Add @Version, ID type, event publishing | 70% |
| **ftgo-kitchen-service** | 🟡 MEDIUM | Add @Version, ID type, saga participation | 65% |
| **ftgo-delivery-service** | 🟡 MEDIUM | Add @Version, ID type, event consumption | 65% |
| **ftgo-order-service** | 🔴 HIGH | ID type, saga orchestration, API changes | 60% |
| **ftgo-accounting-service** | 🔴 HIGH | ID type, event sourcing, event store | 50% |
| **ftgo-order-history-service** | 🟢 LOW | ID type in event handlers | 70% |

---

## Change Categories by Layer

### Entity Layer (Domain)
- **Change**: ID type (Long → String), add @Version, add timestamps
- **Complexity**: 🟢 LOW
- **Automation**: 90%
- **Files**: ~15 entity classes

### Repository Layer
- **Change**: Generic type parameter update
- **Complexity**: 🟢 LOW
- **Automation**: 99%
- **Files**: ~10 repository interfaces

### Service Layer
- **Change**: Method signatures, transaction review, retry logic
- **Complexity**: 🟡 MEDIUM
- **Automation**: 70%
- **Files**: ~60 service methods

### Controller Layer (Web)
- **Change**: Path variable types, request/response DTOs
- **Complexity**: 🟡 MEDIUM
- **Automation**: 85%
- **Files**: ~25 controller endpoints

### Messaging Layer
- **Change**: Event schemas, saga states, command handlers
- **Complexity**: 🔴 HIGH
- **Automation**: 60%
- **Files**: ~15 event classes, 3 sagas, ~10 handlers

### API Contracts
- **Change**: DTOs, request/response objects
- **Complexity**: 🟡 MEDIUM (breaking changes)
- **Automation**: 90%
- **Files**: ~30 API classes

### Test Layer
- **Change**: Test data, assertions, mocks
- **Complexity**: 🟡 MEDIUM
- **Automation**: 70%
- **Files**: ~80 test classes

---

## Key Technical Challenges

### Challenge 1: Distributed ID Management
**Problem**: Sequential Long IDs → Random UUID Strings
**Impact**:
- ID generation changes from database-managed to application-managed
- No natural ordering (unless using timestamp-based UUIDs)
- Larger storage footprint (16 bytes vs 8 bytes)

**Solution**:
- Use UUID.randomUUID() for ID generation
- Add createdAt timestamp for ordering needs
- Accept storage trade-off for distributed scalability

---

### Challenge 2: Pessimistic to Optimistic Locking
**Current State**:
- ✅ No pessimistic locks found in codebase
- ✅ Order already uses @Version for optimistic locking
- ❌ Other entities lack @Version

**Required Changes**:
- Add @Version to Consumer, Ticket, Restaurant, Delivery
- Implement retry logic for OptimisticLockException
- Add monitoring for lock contention

**Risk**:
- Low (codebase already follows optimistic pattern)
- Retry logic needed but straightforward

---

### Challenge 3: Transaction Semantics
**Spanner Differences**:
- Max 20,000 mutations per transaction (vs unlimited in MySQL)
- Higher latency for distributed transactions
- Different isolation levels

**Required Changes**:
- Review all @Transactional methods
- Identify large batch operations
- Consider saga pattern for cross-service transactions (already used ✓)

**Risk**:
- Low (FTGO already uses sagas for distributed transactions)
- Possible refactoring needed for large batch operations

---

### Challenge 4: Query Pattern Migration
**Anti-Patterns for Spanner**:
- Offset-based pagination: `OFFSET 1000 LIMIT 20` (scans 1000 rows)
- Non-indexed filters: Full table scans
- Unbounded queries: No LIMIT clause

**Required Changes**:
- Cursor-based pagination: `WHERE created_at < :cursor LIMIT 20`
- Explicit indexes for all filter columns
- Add LIMIT to all list queries

**Risk**:
- Medium (requires query pattern analysis)
- Spring Data JPA auto-queries may need custom implementations

---

## Automation Strategy

### Recommended Tooling

**Primary: OpenRewrite (Java AST Refactoring)**
- **Use For**: Entity changes, repository updates, service signatures
- **Confidence**: 85-95%
- **Setup**: Create custom recipes for ID type migration

**Secondary: Custom LLM-Based Transformer**
- **Use For**: DTO updates, test data, comparison operators
- **Confidence**: 75-85%
- **Setup**: Prompt engineering for context-aware changes

**Tertiary: Regex-Based Scripts**
- **Use For**: Simple pattern replacements
- **Confidence**: 60-70%
- **Setup**: Quick wins for well-defined patterns

---

### Automation Workflow

**Step 1: Run AST Transformations**
- Entity ID type changes
- Add @Version fields
- Add timestamp fields
- Repository generic types

**Step 2: Run Type Propagation**
- Service method signatures
- Controller parameters
- DTO fields

**Step 3: Run Pattern Replacements**
- Comparison operators (== → .equals())
- Test data generation

**Step 4: Manual Refinements**
- Event dual-field strategy
- Retry logic
- Transaction review
- Query optimization

**Step 5: Testing**
- Unit tests
- Integration tests
- Saga tests
- API compatibility tests

---

## Risk Matrix

### High Risk (Require Expert Review)

| Risk | Impact if Missed | Mitigation |
|------|------------------|------------|
| **Event schema incompatibility** | Consumer failures, message processing errors | Dual-field strategy, extensive testing |
| **Breaking API changes** | Client application failures | Versioning, coordinated deployment |
| **Saga state serialization** | Distributed transaction failures | Saga flow testing, rollback testing |
| **Optimistic lock storms** | Service degradation | Retry monitoring, backoff tuning |

### Medium Risk (Code Review Required)

| Risk | Impact if Missed | Mitigation |
|------|------------------|------------|
| **Missed ID type conversions** | Type errors at runtime | Comprehensive code review |
| **Foreign key mismatches** | Join failures | Referential integrity checks |
| **Null-safety issues** | NullPointerExceptions | .equals() null checks |
| **Query performance** | Slow queries | Query analysis, indexing |

### Low Risk (Automation Safe)

| Risk | Impact if Missed | Mitigation |
|------|------------------|------------|
| **Repository interface types** | Compilation errors | Compile-time verification |
| **Timestamp field missing** | Query limitations | Simple validation script |
| **Test data format** | Test failures | Iterative test fixing |

---

## Summary: Application Changes Breakdown

```
┌────────────────────────────────────────────────────────┐
│        Application Code Changes Distribution            │
└────────────────────────────────────────────────────────┘

Total Code Changes: 100%

├── High Complexity (30%)
│   ├── Event schema evolution: 10%
│   ├── Saga state changes: 8%
│   ├── Event sourcing (accounting): 7%
│   └── Transaction review: 5%
│
├── Medium Complexity (40%)
│   ├── ID type migration: 15%
│   ├── Optimistic locking: 8%
│   ├── Query patterns: 7%
│   └── API contracts: 10%
│
└── Low Complexity (30%)
    ├── Repository updates: 5%
    ├── Timestamp fields: 5%
    ├── Comparison operators: 8%
    └── Test data: 12%

Automation Coverage:
├── Fully Automated (30%): Repository, timestamps, basic types
├── Automated + Review (35%): Entities, services, controllers, DTOs
├── Partially Automated (20%): Sagas, events, tests
└── Manual Required (15%): Transactions, retry logic, optimization
```

---

## Good News: Existing Spanner-Friendly Patterns ✅

**1. Optimistic Locking Already Present**
- Order entity has @Version field
- No pessimistic locks (@Lock) found
- State machines used instead (Ticket)

**2. Saga Pattern for Distributed Transactions**
- Already uses Eventuate Tram Sagas
- No distributed two-phase commit
- Compensation logic already implemented

**3. Database-per-Service**
- Clean service boundaries
- No cross-database joins
- Independent migration possible

**4. Denormalized Data**
- Restaurant data replicated where needed
- Avoids cross-service joins
- Spanner-friendly pattern

**5. Event-Driven Architecture**
- Already uses async messaging
- Service decoupling via events
- Good fit for distributed database

---

## Final Recommendation

### Application Migration Viability: ✅ **FEASIBLE**

**Strengths**:
1. ✅ High automation potential (60-70%)
2. ✅ Already uses optimistic locking patterns
3. ✅ No pessimistic locks to remove
4. ✅ Saga pattern already implemented
5. ✅ Clean service boundaries

**Challenges**:
1. 🔴 Event schema evolution (backward compatibility)
2. 🔴 Saga state serialization changes
3. 🟡 API breaking changes (ID format)
4. 🟡 Query pattern optimization

**Critical Success Factors**:
1. Expert code review for automated changes
2. Comprehensive saga testing
3. Event backward compatibility strategy
4. API versioning or coordinated deployment
5. Thorough integration testing

**Recommended Approach**:
- Automate 60-70% of code changes using OpenRewrite
- Manual review all automated changes
- Pilot on low-complexity service first (ftgo-restaurant-service)
- Incremental migration service-by-service
- Comprehensive testing at each stage

---

*Document Focus: Application Code Changes Only*
*Excludes: Database schema design, data migration, infrastructure setup*
*Version: 2.0*
*Last Updated: 2025-10-06*
