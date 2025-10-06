# FTGO Application - Dependency Mapping Report

## Executive Summary

This report provides a comprehensive dependency mapping for the FTGO microservices application, analyzing four critical dependency dimensions:
1. **Service-to-Service Dependencies**
2. **Internal vs External Dependencies**
3. **Service-to-Database Dependencies**
4. **Database-to-Database Dependencies**

The report enables impact analysis for code changes and provides guidance for bucketing changes to minimize risk.

---

## 1. Service-to-Service Dependencies

### 1.1 Dependency Graph Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     FTGO Service Dependencies                    │
└─────────────────────────────────────────────────────────────────┘

API Gateways (Entry Points)
├── ftgo-api-gateway (REST)
│   ├──[REST]──> ftgo-order-service
│   ├──[REST]──> ftgo-kitchen-service
│   ├──[REST]──> ftgo-delivery-service
│   ├──[REST]──> ftgo-accounting-service
│   └──[REST]──> ftgo-order-history-service
│
└── ftgo-api-gateway-graphql (GraphQL)
    ├──[REST]──> ftgo-order-service
    ├──[REST]──> ftgo-consumer-service
    └──[REST]──> ftgo-restaurant-service

Core Services (Orchestrators)
├── ftgo-order-service (SAGA ORCHESTRATOR)
│   ├──[Saga Commands]──> ftgo-consumer-service
│   ├──[Saga Commands]──> ftgo-kitchen-service
│   ├──[Saga Commands]──> ftgo-accounting-service
│   └──[Saga Commands]──> ftgo-restaurant-service (read replica)
│
└── ftgo-delivery-service
    └──[Events]──> ftgo-kitchen-service

Supporting Services (Participants)
├── ftgo-consumer-service
│   └──[Command Replies]──> ftgo-order-service
│
├── ftgo-kitchen-service
│   └──[Command Replies]──> ftgo-order-service
│
├── ftgo-accounting-service (Event Sourced)
│   └──[Command Replies]──> ftgo-order-service
│
└── ftgo-restaurant-service
    └──[Domain Events]──> All services (via Kafka)

CQRS Views
└── ftgo-order-history-service
    └──[Subscribes to Events]──> ftgo-order-service
```

### 1.2 Detailed Service Dependencies

#### **ftgo-order-service** (Central Orchestrator)
- **Role**: Saga orchestrator for order workflows
- **Outbound Dependencies**:
  - `ftgo-consumer-service` - Validates consumer eligibility
  - `ftgo-kitchen-service` - Creates/manages tickets
  - `ftgo-accounting-service` - Authorizes payments
  - `ftgo-restaurant-service` - Reads restaurant information
- **Communication Patterns**:
  - Saga commands (point-to-point via Kafka)
  - Domain events (publish/subscribe)
  - gRPC API (exposes)
  - REST API (exposes)
- **Sagas Orchestrated**:
  1. CreateOrderSaga
  2. CancelOrderSaga
  3. ReviseOrderSaga

#### **ftgo-consumer-service**
- **Role**: Saga participant - consumer validation
- **Inbound Dependencies**:
  - Commands from `ftgo-order-service`
- **Outbound Dependencies**: None (terminal participant)
- **Communication Patterns**:
  - Command handlers (receives)
  - Command replies (sends)

#### **ftgo-kitchen-service**
- **Role**: Saga participant - kitchen operations
- **Inbound Dependencies**:
  - Commands from `ftgo-order-service`
  - Events from `ftgo-delivery-service`
- **Outbound Dependencies**:
  - Command replies to `ftgo-order-service`
- **Communication Patterns**:
  - Command handlers
  - Event publishers

#### **ftgo-accounting-service**
- **Role**: Saga participant - payment authorization (Event Sourced)
- **Inbound Dependencies**:
  - Commands from `ftgo-order-service`
- **Outbound Dependencies**:
  - Command replies to `ftgo-order-service`
  - Domain events (account changes)
- **Communication Patterns**:
  - Command handlers
  - Event sourcing events

#### **ftgo-restaurant-service**
- **Role**: Master data service for restaurants
- **Inbound Dependencies**:
  - REST calls from `ftgo-api-gateway-graphql`
  - REST calls for restaurant creation
- **Outbound Dependencies**:
  - Domain events (restaurant created/updated)
- **Communication Patterns**:
  - REST API (exposes)
  - Domain events (publishes)
  - Data replication to other services

#### **ftgo-delivery-service**
- **Role**: Manages delivery operations
- **Inbound Dependencies**:
  - Events from `ftgo-order-service`
  - Events from `ftgo-kitchen-service`
  - Events from `ftgo-restaurant-service`
- **Outbound Dependencies**:
  - Domain events (delivery status)
- **Communication Patterns**:
  - Event handlers (subscribes)
  - Event publishers

#### **ftgo-order-history-service** (CQRS View)
- **Role**: Materialized view for order history queries
- **Inbound Dependencies**:
  - Events from `ftgo-order-service` (OrderCreated, OrderAuthorized, OrderCancelled, OrderRejected)
- **Outbound Dependencies**:
  - REST API queries from `ftgo-api-gateway`
- **Communication Patterns**:
  - Event subscribers (eventually consistent)
  - REST API (exposes read-only)

#### **ftgo-api-gateway**
- **Role**: API composition and routing
- **Inbound Dependencies**: External clients
- **Outbound Dependencies**:
  - `ftgo-order-service` - Order operations
  - `ftgo-order-history-service` - Order history queries
  - `ftgo-kitchen-service` - Ticket information
  - `ftgo-delivery-service` - Delivery status
  - `ftgo-accounting-service` - Bill information
- **Communication Patterns**:
  - Reactive REST calls (WebClient)
  - API composition (aggregates responses)

#### **ftgo-api-gateway-graphql**
- **Role**: GraphQL API with schema stitching
- **Inbound Dependencies**: External GraphQL clients
- **Outbound Dependencies**:
  - `ftgo-consumer-service` - Consumer queries/mutations
  - `ftgo-order-service` - Order queries
  - `ftgo-restaurant-service` - Restaurant queries
- **Communication Patterns**:
  - GraphQL resolvers
  - REST proxies to backend services
  - DataLoader for batching

### 1.3 Message Flow Patterns

#### Synchronous (Request/Reply)
```
Client ──REST──> API Gateway ──REST──> Service ──Response──> API Gateway ──Response──> Client
```

#### Asynchronous (Saga Orchestration)
```
Order Service ──Command──> Participant Service
     │                            │
     │                     [Process & Reply]
     │                            │
     └────── Command Reply ───────┘
```

#### Event-Driven (Pub/Sub)
```
Service A ──Domain Event──> Kafka ──Event──> Service B (Subscriber)
                                  └──Event──> Service C (Subscriber)
```

---

## 2. Internal vs External Dependencies

### 2.1 Internal Module Dependencies

#### Shared Internal Modules (All Java Services)
```
Service Dependencies Tree:

ftgo-*-service/
├── ftgo-common                      # Core domain objects
│   ├── Money
│   ├── Address
│   └── Common utilities
│
├── ftgo-common-jpa                  # JPA configurations
│   ├── Entity base classes
│   └── JPA converters
│
├── common-swagger                   # Swagger/OpenAPI config
│   └── Swagger customization
│
├── ftgo-*-service-api              # Service's published API
│   ├── Commands (if saga participant)
│   ├── Events
│   └── DTOs
│
└── ftgo-*-service-api-spec         # JSON Schema specs
    └── Message schemas for validation
```

#### Service-Specific Internal Dependencies

**ftgo-order-service**:
- `ftgo-consumer-service-api` - Consumer commands/events
- `ftgo-consumer-service-api-spec` - Consumer schemas
- `ftgo-accounting-service-api` - Accounting commands/events
- `ftgo-accounting-service-api-spec` - Accounting schemas
- `ftgo-kitchen-service-api` - Kitchen commands/events
- `ftgo-restaurant-service-api` - Restaurant commands/events
- `ftgo-restaurant-service-api-spec` - Restaurant schemas
- `ftgo-order-service-api` - Own API (for self-commands in sagas)

**ftgo-kitchen-service**:
- `ftgo-kitchen-service-api` - Own API
- `ftgo-restaurant-service-api` - Restaurant events
- `ftgo-restaurant-service-api-spec` - Restaurant schemas

**ftgo-consumer-service**:
- `ftgo-consumer-service-api` - Own API
- `ftgo-consumer-service-api-spec` - Own schemas

**ftgo-accounting-service**:
- `ftgo-accounting-service-api` - Own API

**ftgo-restaurant-service**:
- `ftgo-restaurant-service-api` - Own API
- `ftgo-restaurant-service-api-spec` - Own schemas
- `ftgo-common` - Common domain objects

**ftgo-delivery-service**:
- `ftgo-delivery-service-api` - Own API
- `ftgo-kitchen-service-api` - Kitchen events
- `ftgo-restaurant-service-api` - Restaurant events
- `ftgo-restaurant-service-api-spec` - Restaurant schemas
- `ftgo-order-service-api` - Order events

**ftgo-order-history-service**:
- `ftgo-order-service-api` - Order events (no JPA)

**ftgo-api-gateway**:
- None (reactive, no shared modules)

**ftgo-api-gateway-graphql**:
- None (Node.js/TypeScript)

### 2.2 External Library Dependencies

#### Core Framework Dependencies
| Library/Framework | Version | Used By | Purpose |
|-------------------|---------|---------|---------|
| **Spring Boot** | 2.2.6.RELEASE | All Java services | Core framework |
| **Spring Data JPA** | 2.2.6.RELEASE | Services with MySQL | ORM |
| **Spring Cloud Gateway** | 2.0.0.RELEASE | ftgo-api-gateway | Reactive API gateway |
| **Spring Cloud Contract** | 2.2.0.RELEASE | All services | Consumer-driven contracts |
| **Spring Cloud Sleuth** | 2.2.2.RELEASE | order, delivery, api-gateway | Distributed tracing |

#### Eventuate Platform (Event-Driven Architecture)
| Library | Version | Used By | Purpose |
|---------|---------|---------|---------|
| **eventuate-platform** | 2022.0.RELEASE | All services | Platform BOM |
| **eventuate-common** | 0.15.0.RELEASE | All services | Common utilities |
| **eventuate-tram-spring-jdbc-kafka** | Platform | Services with MySQL | Transactional messaging |
| **eventuate-tram-sagas** | 0.19.0.RELEASE | order-service | Saga orchestration |
| **eventuate-client** | Platform | accounting-service | Event sourcing |

#### Messaging & Event Infrastructure
| Library | Version | Used By | Purpose |
|---------|---------|---------|---------|
| **Apache Kafka** | 2.3.0 | All services | Message broker |
| **kafka-clients** | 2.3.0 | All services | Kafka client library |

#### Database Drivers
| Driver | Version | Used By | Purpose |
|--------|---------|---------|---------|
| **mysql-jdbc-driver** | (via Boot) | JPA services | MySQL connectivity |
| **aws-java-sdk-dynamodb** | 1.11.158 | order-history-service | DynamoDB client |

#### Communication Protocols
| Library | Version | Used By | Purpose |
|---------|---------|---------|---------|
| **gRPC** | 1.47.0 | order-service | High-performance RPC |
| **protobuf** | 3.20.1 | order-service | Protocol buffers |
| **graphql** | 0.13.2 | api-gateway-graphql | GraphQL schema |
| **apollo-server-express** | 1.3.2 | api-gateway-graphql | GraphQL server |

#### Observability
| Library | Version | Used By | Purpose |
|---------|---------|---------|---------|
| **Zipkin** | 2.21 (image) | order, delivery, gateway | Distributed tracing |
| **Micrometer** | 1.0.4 | All services | Metrics |
| **micrometer-registry-prometheus** | 1.0.4 | All services | Prometheus metrics |

#### Testing Libraries
| Library | Used By | Purpose |
|---------|---------|---------|
| **JUnit** | All Java services | Unit testing |
| **Spring Boot Test** | All services | Integration testing |
| **REST Assured** | Services with REST | API testing |
| **WireMock** | Services with contracts | Mock HTTP services |
| **Cucumber** | order, delivery services | BDD component tests |
| **Jest** | api-gateway-graphql | JavaScript testing |

### 2.3 Dependency Impact Matrix

| Change Type | Internal Impact | External Impact | Risk Level |
|-------------|----------------|-----------------|------------|
| **Update Spring Boot version** | All Java services | High compatibility risk | HIGH |
| **Update Eventuate Platform** | All services | Messaging protocol changes | HIGH |
| **Update Kafka version** | All services | Message broker compatibility | HIGH |
| **Add field to API contract** | Consumers of that API | None | MEDIUM |
| **Change saga flow** | order-service only | Participant services | HIGH |
| **Update service internal logic** | Single service | None (if API stable) | LOW |
| **Update shared module** | All dependent services | None | MEDIUM |

---

## 3. Service-to-Database Dependencies

### 3.1 Database Ownership Map

```
┌─────────────────────────────────────────────────────────────────┐
│                  Database-per-Service Pattern                    │
└─────────────────────────────────────────────────────────────────┘

MySQL Server (Single Instance, Multiple Schemas)
├── ftgo_consumer_service (Schema)
│   ├── Owner: ftgo-consumer-service
│   └── Tables: consumers, eventuate tables
│
├── ftgo_order_service (Schema)
│   ├── Owner: ftgo-order-service
│   └── Tables: orders, order_line_items, restaurants (replica), eventuate tables
│
├── ftgo_kitchen_service (Schema)
│   ├── Owner: ftgo-kitchen-service
│   └── Tables: tickets, restaurants (replica), eventuate tables
│
├── ftgo_restaurant_service (Schema)
│   ├── Owner: ftgo-restaurant-service
│   └── Tables: restaurants (master), menu_items, eventuate tables
│
├── ftgo_accounting_service (Schema)
│   ├── Owner: ftgo-accounting-service
│   └── Tables: events (event store), entities, snapshots, eventuate tables
│
├── ftgo_delivery_service (Schema)
│   ├── Owner: ftgo-delivery-service
│   └── Tables: deliveries, restaurants (replica), eventuate tables
│
└── eventuate (Schema)
    ├── Owner: CDC Service
    └── Tables: Common CDC metadata

DynamoDB Local
└── ftgoorderhistoryservice (DynamoDB)
    ├── Owner: ftgo-order-history-service
    └── Tables: Order views (denormalized)
```

### 3.2 Service Database Details

| Service | Database Type | Schema Name | Access Pattern | Persistence Pattern |
|---------|--------------|-------------|----------------|---------------------|
| **ftgo-consumer-service** | MySQL | ftgo_consumer_service | JPA/Hibernate | Traditional ORM |
| **ftgo-order-service** | MySQL | ftgo_order_service | JPA/Hibernate | Traditional ORM + Saga state |
| **ftgo-kitchen-service** | MySQL | ftgo_kitchen_service | JPA/Hibernate | Traditional ORM |
| **ftgo-restaurant-service** | MySQL | ftgo_restaurant_service | JPA/Hibernate | Traditional ORM (master data) |
| **ftgo-accounting-service** | MySQL | ftgo_accounting_service | Event Store | Event Sourcing |
| **ftgo-delivery-service** | MySQL | ftgo_delivery_service | JPA/Hibernate | Traditional ORM |
| **ftgo-order-history-service** | DynamoDB | N/A (NoSQL) | DynamoDB SDK | Key-value store (CQRS view) |
| **ftgo-api-gateway** | None | N/A | N/A | Stateless |
| **ftgo-api-gateway-graphql** | None | N/A | N/A | Stateless |

### 3.3 Database Connection Configuration

#### MySQL Services
```
Connection Pattern:
jdbc:mysql://mysql/<schema_name>

User Pattern:
<schema_name>_user

Example:
- URL: jdbc:mysql://mysql/ftgo_order_service
- User: ftgo_order_service_user
- Password: ftgo_order_service_password
```

#### DynamoDB Service
```
Connection Pattern:
AWS_DYNAMODB_ENDPOINT_URL: http://dynamodblocal:8000
AWS_REGION: us-west-2 (local override)

Service: ftgo-order-history-service
```

### 3.4 Schema Isolation and Security

- **Schema-per-Service**: Each service has its own MySQL schema
- **Dedicated Users**: Each schema has a dedicated MySQL user
- **Least Privilege**: Users can only access their own schema
- **No Cross-Schema Queries**: Services cannot directly query other services' databases
- **CDC Tables**: Each schema has Eventuate Tram outbox tables for transactional messaging

### 3.5 Database Initialization

```bash
# Script: mysql/compile-schema-per-service.sh
# Creates schemas and users for:
for schema in ftgo_accounting_service \
              ftgo_consumer_service \
              ftgo_order_service \
              ftgo_kitchen_service \
              ftgo_restaurant_service \
              ftgo_delivery_service; do
  # Create user and schema
  # Grant privileges
  # Apply Eventuate Tram schema template
done
```

---

## 4. Database-to-Database Dependencies

### 4.1 Cross-Database Data Flow

Even though each service has its own database, data flows between databases through:

1. **CDC (Change Data Capture) via MySQL Binlog**
2. **Kafka Message Broker**
3. **Eventuate Tram Framework**

```
┌─────────────────────────────────────────────────────────────────┐
│            Database-to-Database via CDC + Kafka                  │
└─────────────────────────────────────────────────────────────────┘

[Service A DB] ──(1)──> [Outbox Table] ──(2)──> [CDC Service]
                                                      │
                                                   (3) Read Binlog
                                                      │
                                                      ▼
                                                  [Kafka Topic]
                                                      │
                                                   (4) Subscribe
                                                      │
                                                      ▼
[Service B] ──(5)──> [Process Event] ──(6)──> [Service B DB]
```

### 4.2 Database Replication Patterns

#### Pattern 1: Restaurant Data Replication (Master → Replicas)

**Master Database**: `ftgo_restaurant_service.restaurants`

**Replica Databases**:
- `ftgo_order_service.restaurants` (read-only replica)
- `ftgo_kitchen_service.restaurants` (read-only replica)
- `ftgo_delivery_service.restaurants` (read-only replica)

**Replication Flow**:
```
ftgo_restaurant_service DB
  └── Restaurant created/updated
      └── Domain Event published (RestaurantCreated/RestaurantMenuRevised)
          └── Kafka
              ├──> ftgo-order-service subscribes
              │    └── Updates local restaurant replica
              ├──> ftgo-kitchen-service subscribes
              │    └── Updates local restaurant replica
              └──> ftgo-delivery-service subscribes
                   └── Updates local restaurant replica
```

**Why Replicate?**
- Order service needs restaurant menu for order validation
- Kitchen service needs restaurant info for ticket creation
- Delivery service needs restaurant location
- Avoids cross-service database queries
- Eventual consistency acceptable for restaurant master data

#### Pattern 2: Order Events → Order History (CQRS View)

**Source Database**: `ftgo_order_service.orders`

**Target Database**: `dynamodb.ftgoorderhistoryservice`

**Replication Flow**:
```
ftgo_order_service DB
  └── Order state changes (Created, Authorized, Cancelled, Rejected)
      └── Domain Events via CDC
          └── Kafka
              └── ftgo-order-history-service subscribes
                  └── Updates DynamoDB view (denormalized)
```

**Data Transformation**:
- Source: Normalized relational tables (orders, order_line_items)
- Target: Denormalized NoSQL documents optimized for queries
- Pattern: CQRS (Command Query Responsibility Segregation)

#### Pattern 3: Saga Coordination (Cross-Database Transaction)

**Databases Involved**:
- `ftgo_order_service` (saga orchestrator state)
- `ftgo_consumer_service` (consumer validation)
- `ftgo_kitchen_service` (ticket creation)
- `ftgo_accounting_service` (payment authorization - event sourced)

**Transaction Flow (CreateOrderSaga)**:
```
1. ftgo_order_service DB
   └── Create Order (APPROVAL_PENDING)
   └── Publish OrderCreated event
   └── Start CreateOrderSaga

2. ftgo_consumer_service DB
   └── Validate consumer via command
   └── Reply: Consumer valid

3. ftgo_kitchen_service DB
   └── Create Ticket via command
   └── Reply: Ticket created

4. ftgo_accounting_service DB (Event Store)
   └── Authorize payment via command
   └── Append AccountDebited event
   └── Reply: Payment authorized

5. ftgo_kitchen_service DB
   └── Confirm Ticket

6. ftgo_order_service DB
   └── Update Order (APPROVED)
   └── Saga complete
```

**Consistency Guarantees**:
- Each database change is local ACID transaction
- Cross-database consistency via saga compensations
- Eventual consistency across services
- At-least-once message delivery

### 4.3 CDC Service Database Connections

The CDC Service reads from ALL MySQL schemas:

```yaml
CDC Service Configuration:
  Pipeline 1: ftgo_consumer_service  (eventuate-tram)
  Pipeline 2: ftgo_order_service     (eventuate-tram)
  Pipeline 3: ftgo_kitchen_service   (eventuate-tram)
  Pipeline 4: ftgo_restaurant_service (eventuate-tram)
  Pipeline 5: ftgo_accounting_service (eventuate-tram)
  Pipeline 6: ftgoorderhistoryservice (eventuate-tram)
  Pipeline 7: ftgo_accounting_service (eventuate-local - for event sourcing)
  Pipeline 8: ftgo_delivery_service  (eventuate-tram)

Reader: MySQL Binlog Reader
  - Reads binlog from mysql:3306
  - Monitors outbox tables in each schema
  - Publishes to Kafka topics
```

### 4.4 Data Consistency Patterns

| Pattern | Databases Involved | Consistency Model | Mechanism |
|---------|-------------------|-------------------|-----------|
| **Restaurant Replication** | restaurant → order, kitchen, delivery | Eventual | Domain events |
| **Order CQRS View** | order → dynamodb | Eventual | Domain events |
| **Saga Transactions** | order, consumer, kitchen, accounting | Eventual (compensating) | Saga commands |
| **Event Sourcing** | accounting (event store) | Strong (per aggregate) | Event store |
| **API Composition** | Multiple DBs (read via services) | Real-time inconsistency | Gateway aggregation |

### 4.5 Database Dependency Impact

| Change Scenario | Affected Databases | Impact Type | Mitigation |
|-----------------|-------------------|-------------|------------|
| **Restaurant menu change** | restaurant, order, kitchen, delivery | Eventual propagation delay | Accept eventual consistency |
| **Order created** | order, consumer, kitchen, accounting | Saga coordination required | Compensating transactions |
| **Consumer validation fails** | order, consumer | Saga rollback | Compensating commands |
| **CDC service down** | All MySQL → Kafka | Message delivery delayed | Kafka retains messages, catch up when recovered |
| **MySQL binlog full** | All services | CDC stops | Monitor binlog size, retention |
| **DynamoDB unavailable** | Order history queries fail | Read-only service degraded | Use order-service REST API as fallback |

---

## 5. Impact Analysis & Change Buckets

### 5.1 Service Change Impact Buckets

#### **Bucket 1: Low Impact (Isolated Changes)**
Changes that affect a single service with no external API changes.

**Services in Bucket**:
- Individual service internal logic changes
- Database schema changes (within service boundary)
- Internal refactoring
- Bug fixes (non-API)

**Testing Strategy**:
- Unit tests
- Integration tests (service + DB)
- No cross-service testing needed

**Example Changes**:
- Add new field to Order aggregate (internal only)
- Optimize Kitchen service query performance
- Fix bug in Consumer service validation logic

**Deployment Risk**: LOW
**Rollback**: Easy (single service)

---

#### **Bucket 2: Medium Impact (API Contract Changes)**
Changes to service APIs that affect consumers.

**Services in Bucket**:
- `*-api` modules
- REST endpoint changes
- Event schema additions (backward compatible)
- Command schema additions (backward compatible)

**Testing Strategy**:
- Contract tests (Spring Cloud Contract)
- Consumer-driven contract verification
- Integration tests with mocks
- API compatibility tests

**Example Changes**:
- Add optional field to OrderCreatedEvent
- Add new REST endpoint to Consumer service
- Add new command to Kitchen service API

**Deployment Risk**: MEDIUM
**Rollback**: Requires coordination with consumers
**Migration Strategy**: Blue-green deployment, versioned APIs

---

#### **Bucket 3: High Impact (Saga & Orchestration Changes)**
Changes to saga flows or orchestration logic.

**Services in Bucket**:
- `ftgo-order-service` (saga orchestrator)
- Saga participants (consumer, kitchen, accounting)
- CreateOrderSaga, CancelOrderSaga, ReviseOrderSaga

**Testing Strategy**:
- Saga integration tests
- Component tests (full flow)
- End-to-end tests
- Saga compensation tests

**Example Changes**:
- Add new step to CreateOrderSaga
- Modify saga compensation logic
- Change saga participant command structure

**Deployment Risk**: HIGH
**Rollback**: Complex (requires all participants to be compatible)
**Migration Strategy**:
  - Versioned sagas
  - Parallel run (old + new saga)
  - Gradual migration

---

#### **Bucket 4: Critical Impact (Platform & Infrastructure)**
Changes to shared infrastructure and platform dependencies.

**Components in Bucket**:
- Eventuate Platform version upgrade
- Spring Boot version upgrade
- Kafka version upgrade
- MySQL version upgrade
- Shared modules (ftgo-common, ftgo-common-jpa)

**Testing Strategy**:
- Full regression testing
- End-to-end tests
- Performance tests
- Backward compatibility verification
- Staged rollout

**Example Changes**:
- Upgrade Eventuate Platform from 2022.0 to 2023.0
- Upgrade Spring Boot 2.x to 3.x
- Update Kafka clients

**Deployment Risk**: CRITICAL
**Rollback**: Very complex (all services affected)
**Migration Strategy**:
  - Extensive testing in staging
  - Canary deployment
  - Feature flags
  - Rollback plan with data migration

---

### 5.2 Database Change Impact Buckets

#### **Bucket DB-1: Single Database Schema (Low Impact)**
Changes within a service's database schema.

**Changes**:
- Add table/column (backward compatible)
- Add index
- Modify non-critical constraints

**Affected Services**: Single service only

**Testing**:
- Database migration scripts
- Integration tests with new schema
- Rollback scripts

**Deployment**:
- Schema change first, then code
- Or use JPA auto-DDL (dev only)

---

#### **Bucket DB-2: Event Schema Changes (Medium Impact)**
Changes to event payloads that flow between databases.

**Changes**:
- Add field to domain event
- Add new event type
- Modify event structure (backward compatible)

**Affected Databases**:
- Source database (publisher)
- Target databases (subscribers)

**Testing**:
- Event serialization tests
- Backward compatibility tests
- Subscriber handling of old/new events

**Deployment**:
- Deploy consumers first (accept new fields)
- Then deploy publisher

---

#### **Bucket DB-3: Replicated Data Changes (High Impact)**
Changes to replicated data (e.g., Restaurant data).

**Changes**:
- Restaurant entity schema change
- Restaurant replication logic change

**Affected Databases**:
- ftgo_restaurant_service (master)
- ftgo_order_service (replica)
- ftgo_kitchen_service (replica)
- ftgo_delivery_service (replica)

**Testing**:
- Replication lag monitoring
- Eventual consistency verification
- Replica update tests

**Deployment**:
- Update master service first
- Update replica services (accept new fields)
- Monitor replication lag

---

#### **Bucket DB-4: CDC & Messaging Changes (Critical Impact)**
Changes to CDC configuration or messaging infrastructure.

**Changes**:
- CDC service upgrade
- Kafka topic configuration
- Message broker changes
- Transactional outbox table changes

**Affected Databases**: ALL

**Testing**:
- Full integration testing
- Message delivery verification
- Idempotency tests
- Failure recovery tests

**Deployment**:
- Blue-green deployment
- Canary with traffic shadowing
- Monitor message delivery metrics

---

### 5.3 Change Checklist by Bucket

#### For Bucket 1 (Low Impact - Single Service):
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Database migrations tested
- [ ] Service can be deployed independently
- [ ] Rollback plan documented

#### For Bucket 2 (Medium Impact - API Contracts):
- [ ] Contract tests updated
- [ ] Consumer compatibility verified
- [ ] API documentation updated
- [ ] Backward compatibility ensured
- [ ] Consumer notification sent
- [ ] Gradual rollout plan

#### For Bucket 3 (High Impact - Sagas):
- [ ] Saga flow documented
- [ ] Compensation logic tested
- [ ] All participants updated
- [ ] End-to-end saga tests pass
- [ ] Rollback saga tested
- [ ] Deployment sequence planned
- [ ] Monitoring dashboards updated

#### For Bucket 4 (Critical Impact - Platform):
- [ ] Full regression suite passes
- [ ] Performance benchmarks completed
- [ ] Staging environment validated
- [ ] All services compatible with new version
- [ ] Rollback tested in staging
- [ ] Canary deployment plan
- [ ] Incident response plan
- [ ] Communication plan for stakeholders

---

### 5.4 Inter-Bucket Dependencies

```
Bucket 4 (Platform)
    ↓ (affects all)
Bucket 3 (Sagas) ←→ Bucket 2 (APIs)
    ↓                    ↓
Bucket 1 (Single Service)
    ↓
Bucket DB-1 (Single DB) → Bucket DB-2 (Events) → Bucket DB-3 (Replicated)
                                                         ↓
                                                  Bucket DB-4 (CDC)
```

**Rule**: Always consider upstream buckets when making changes in downstream buckets.

---

### 5.5 Recommended Change Strategy

1. **Start Small (Bucket 1)**: Prove changes in isolated services first
2. **Expand Gradually (Bucket 2)**: Roll out API changes with versioning
3. **Coordinate Carefully (Bucket 3)**: Saga changes require all participants ready
4. **Plan Extensively (Bucket 4)**: Platform changes need comprehensive testing

---

## 6. Service Dependency Matrix

| Service | Depends On (Compile Time) | Communicates With (Runtime) | Database | Critical Path |
|---------|---------------------------|----------------------------|----------|---------------|
| **consumer-service** | common-swagger, ftgo-common-jpa, consumer-api | order-service (replies) | ftgo_consumer_service | ✓ (saga participant) |
| **restaurant-service** | common-swagger, ftgo-common, ftgo-common-jpa, restaurant-api | All (via events) | ftgo_restaurant_service | Reference data |
| **order-service** | common-swagger, ftgo-common-jpa, order-api, consumer-api, kitchen-api, accounting-api, restaurant-api | consumer, kitchen, accounting, restaurant | ftgo_order_service | ✓✓ (orchestrator) |
| **kitchen-service** | common-swagger, ftgo-common-jpa, kitchen-api, restaurant-api | order-service (replies), delivery-service | ftgo_kitchen_service | ✓ (saga participant) |
| **accounting-service** | common-swagger, accounting-api | order-service (replies) | ftgo_accounting_service | ✓ (saga participant) |
| **delivery-service** | common-swagger, ftgo-common, ftgo-common-jpa, delivery-api, kitchen-api, restaurant-api, order-api | kitchen, restaurant, order (events) | ftgo_delivery_service | Post-order |
| **order-history-service** | common-swagger, order-api (no JPA) | order-service (events) | DynamoDB | Query only (CQRS) |
| **api-gateway** | None | order, kitchen, delivery, accounting, order-history | None | Entry point |
| **api-gateway-graphql** | None (Node.js) | consumer, order, restaurant | None | Entry point (alt) |

**Legend**:
- ✓✓ = Critical (orchestrator)
- ✓ = Critical (saga participant)
- Reference data = Master data service
- Query only = Read-only CQRS view

---

## 7. Key Insights for Change Management

### 7.1 Critical Dependencies to Monitor

1. **Order Service → All Saga Participants**
   - Any change to saga flow requires coordination
   - Versioning strategy essential

2. **Restaurant Service → All Services**
   - Master data changes propagate everywhere
   - Eventual consistency acceptable but must be monitored

3. **CDC Service → All MySQL Services**
   - Single point of failure for messaging
   - Must be highly available

4. **Eventuate Platform → All Services**
   - Platform upgrades affect entire system
   - Requires extensive testing

### 7.2 Safe Change Patterns

✅ **Safe to Change**:
- Internal service logic (no API change)
- Add optional fields to events/commands
- Add new endpoints (non-breaking)
- Performance optimizations
- Add new services (if not in critical path)

⚠️ **Risky to Change**:
- Saga orchestration flow
- Required fields in events/commands
- Database schema (in replicated data)
- Message broker configuration

🛑 **Critical - Requires Full Testing**:
- Eventuate Platform version
- Spring Boot major version
- Kafka version
- CDC service configuration
- Saga compensation logic

### 7.3 Deployment Order Recommendations

For coordinated changes across multiple services:

1. **Update *-api modules** (publish contracts)
2. **Update saga participants** (consumers of commands)
3. **Update saga orchestrator** (order-service)
4. **Update event subscribers** (order-history, delivery)
5. **Update API gateways** (last to ensure backend ready)

---

## 8. Recommendations

### 8.1 Monitoring & Observability

- **Service-to-Service**: Monitor Kafka lag, saga completion rates
- **Service-to-Database**: Monitor connection pools, query performance
- **Database-to-Database**: Monitor CDC lag, event processing delays
- **External Dependencies**: Version matrix dashboard, dependency health checks

### 8.2 Change Process

1. **Identify change bucket** using this report
2. **Review dependency matrix** for impacted services
3. **Create compatibility tests** for affected boundaries
4. **Plan deployment sequence** based on dependency graph
5. **Prepare rollback strategy** for each bucket level
6. **Execute in stages** with validation gates

### 8.3 Future Improvements

- **API Versioning**: Implement versioned APIs for breaking changes
- **Contract Testing**: Expand Spring Cloud Contract coverage
- **Dependency Tracking**: Automated dependency graph generation
- **Canary Deployment**: Per-service canary with metrics
- **Feature Flags**: For high-risk changes in critical path services

---

## Appendix A: Quick Reference

### Service Criticality Ranking
1. **ftgo-order-service** (Orchestrator - Cannot fail)
2. **ftgo-consumer-service** (Saga participant - Blocks orders)
3. **ftgo-kitchen-service** (Saga participant - Blocks orders)
4. **ftgo-accounting-service** (Saga participant - Blocks orders)
5. **ftgo-restaurant-service** (Master data - Degrades slowly)
6. **ftgo-delivery-service** (Post-order - Can be eventually consistent)
7. **ftgo-order-history-service** (Query only - Can fallback to order-service)
8. **ftgo-api-gateway** (Entry point - Can route to services directly)
9. **ftgo-api-gateway-graphql** (Alternative entry point - Optional)

### Database Criticality Ranking
1. **ftgo_order_service** (Saga state - Critical)
2. **ftgo_consumer_service** (Validation - Critical)
3. **ftgo_kitchen_service** (Fulfillment - Critical)
4. **ftgo_accounting_service** (Payment - Critical)
5. **ftgo_restaurant_service** (Master data - Important)
6. **ftgo_delivery_service** (Post-order - Important)
7. **DynamoDB** (CQRS view - Can fallback)

---

## Document Metadata

- **Generated**: 2025-10-06
- **Application**: FTGO Microservices (ftgo-application)
- **Version**: Based on Spring Boot 2.2.6.RELEASE, Eventuate 2022.0.RELEASE
- **Purpose**: Dependency mapping for change impact analysis and safe deployments
