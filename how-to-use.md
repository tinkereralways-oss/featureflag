# Feature Flag Management System -- How to Use

A comprehensive, hands-on guide for developers. Follow along step by step from zero to a fully running feature flag system.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [Quick Start](#3-quick-start)
4. [Building the Project](#4-building-the-project)
5. [Running Locally with Docker Compose](#5-running-locally-with-docker-compose)
6. [Using the Flag Server](#6-using-the-flag-server)
7. [Using the Web Dashboard](#7-using-the-web-dashboard)
8. [Integrating the SDK into Your Service](#8-integrating-the-sdk-into-your-service)
9. [Understanding Two-Phase Activation](#9-understanding-two-phase-activation)
10. [Testing](#10-testing)
11. [Running the Load Test](#11-running-the-load-test)
12. [Running the E2E Test](#12-running-the-e2e-test)
13. [Configuration Reference](#13-configuration-reference)
14. [Project Structure](#14-project-structure)
15. [Troubleshooting](#15-troubleshooting)
16. [Further Reading](#16-further-reading)

---

## 1. Overview

The Feature Flag Management System is a production-grade feature flag platform designed to protect a payment processing platform. It provides centralized flag management with a REST API, a web dashboard, and a Java SDK that consumer services embed to query flag states in real time.

### Key Capabilities

- **Centralized flag management** with REST API and Thymeleaf web dashboard
- **Flag lifecycle state machine**: CREATED -> ACTIVE -> RETIRED -> ARCHIVED
- **Two-phase activation protocol**: Ensures all registered SDK instances acknowledge a flag change before it takes effect (PREPARE -> ACK -> COMMIT/ROLLBACK)
- **Instance self-registration**: SDK instances register with the server, send heartbeats, and are automatically marked unhealthy after silence
- **Real-time updates via Kafka**: Flag changes are broadcast to all SDK instances
- **Request-scoped flag pinning**: Flags are snapshotted at the start of each HTTP request so a single request sees consistent flag values
- **Audit trail**: Every flag change is logged with before/after diffs
- **Staleness detection**: Flags untouched for a configurable number of days are automatically flagged as stale

### Architecture

```
+-------------------+         +-------------------+         +-------------------+
|                   |         |                   |         |                   |
|   Flag Server     |<------->|   Oracle 21c XE   |         |  Sample Consumer  |
|   (Spring Boot)   |         |   (Database)      |         |  (Spring Boot)    |
|   Port 8080       |         |   Port 1521       |         |  Port 8081        |
|                   |         |                   |         |                   |
|  - REST API       |         +-------------------+         |  - Embeds SDK     |
|  - Web Dashboard  |                                       |  - Payment API    |
|  - Kafka Producer |---+                               +---|  - Kafka Consumer |
|  - Liquibase      |   |   +-------------------+      |   |                   |
|    Migrations     |   +-->|                   |<-----+   +-------------------+
+-------------------+       |   Apache Kafka    |
                            |   Port 9092       |
        REST API            |                   |          SDK instances register
    (flag CRUD, toggle,     +-------------------+          via REST, receive flag
     lifecycle, audit,                                     updates via Kafka,
     instance registry)     Zookeeper (Port 2181)          send heartbeats & ACKs
```

### Modules

| Module | Description | Output |
|--------|-------------|--------|
| `feature-flag-server` | Spring Boot 3.2.5 application: REST API, JPA entities, Liquibase migrations, Kafka producer, Thymeleaf dashboard | Executable JAR (Spring Boot fat jar) |
| `feature-flag-sdk` | Library JAR: annotations, AOP aspects, Caffeine cache, Kafka consumer, REST client | Plain JAR (no Boot plugin) |
| `feature-flag-sample-consumer` | Demo Spring Boot app that uses the SDK to feature-flag a payment gateway | Executable JAR |

---

## 2. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java JDK | 17 | Must be JDK 17 (not 21+). `java -version` to verify. |
| Apache Maven | 3.8+ | `mvn -version` to verify. |
| Docker | 20+ | Required for Oracle DB (Testcontainers) and Docker Compose. |
| Docker Compose | v2+ | `docker compose version` to verify. |
| curl | any | For testing the REST API from the command line. |

Ensure these ports are free on your machine:

| Port | Service |
|------|---------|
| 1521 | Oracle XE 21 database |
| 2181 | Zookeeper |
| 9092 | Kafka |
| 8080 | Flag Server |
| 8081 | Sample Consumer |

---

## 3. Quick Start

Get the entire system running in under 5 minutes:

```bash
# 1. Clone the repository
git clone <repository-url>
cd featureFlag

# 2. Build all modules (skip tests for speed)
mvn clean package -DskipTests

# 3. Start everything with Docker Compose
docker compose up -d

# 4. Wait for services to become healthy (~2 minutes for Oracle on first run)
docker compose ps
# Repeat until all services show "healthy"

# 5. Verify the flag server is running
curl http://localhost:8080/api/flags
# Expected: []  (empty array -- no flags created yet)

# 6. Create your first flag
curl -s -X POST http://localhost:8080/api/flags \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-payment-gateway",
    "name": "New Payment Gateway",
    "description": "Switch to Stripe payment processing",
    "owner": "team-payments",
    "staleAfterDays": 90,
    "environments": ["default"]
  }'

# 7. Open the web dashboard
open http://localhost:8080/dashboard
```

---

## 4. Building the Project

### Compile all modules

```bash
mvn compile
```

### Run all tests

Unit tests run without Docker. Integration tests in `feature-flag-server` require Docker (they use Testcontainers with `gvenzl/oracle-xe:21-slim`).

```bash
# All tests (unit + integration)
mvn test -pl feature-flag-server

# SDK tests only (no Docker needed)
mvn test -pl feature-flag-sdk
```

### Run a single test class or method

```bash
# Single class
mvn test -pl feature-flag-server -Dtest=FlagServiceTest

# Single method
mvn test -pl feature-flag-server -Dtest="FlagServiceTest#createFlag_success"
```

### Package all modules

```bash
mvn clean package
```

This produces:
- `feature-flag-server/target/feature-flag-server-1.0.0-SNAPSHOT.jar` -- executable Spring Boot JAR
- `feature-flag-sdk/target/feature-flag-sdk-1.0.0-SNAPSHOT.jar` -- library JAR
- `feature-flag-sample-consumer/target/feature-flag-sample-consumer-1.0.0-SNAPSHOT.jar` -- executable Spring Boot JAR

### Full verify (compile + test + package)

```bash
mvn verify
```

---

## 5. Running Locally with Docker Compose

The `docker-compose.yml` defines four services:

| Service | Image | Purpose |
|---------|-------|---------|
| `oracle` | `gvenzl/oracle-xe:21-slim` | Oracle 21c XE database |
| `zookeeper` | `confluentinc/cp-zookeeper:7.5.3` | Kafka dependency |
| `kafka` | `confluentinc/cp-kafka:7.5.3` | Message broker for flag events |
| `flag-server` | Built from `./feature-flag-server/Dockerfile` | The flag management server |
| `sample-consumer` | Built from `./feature-flag-sample-consumer/Dockerfile` | Demo payment service using the SDK |

### Start

```bash
# Build and start all services
docker compose up -d --build

# Watch logs
docker compose logs -f
```

### Check health

```bash
docker compose ps
```

Expected output when everything is healthy:

```
NAME                   SERVICE            STATUS
featureflag-oracle-1         oracle             running (healthy)
featureflag-zookeeper-1      zookeeper          running
featureflag-kafka-1          kafka              running (healthy)
featureflag-flag-server-1    flag-server        running (healthy)
featureflag-sample-consumer-1 sample-consumer   running (healthy)
```

Oracle takes the longest to start (~90-120 seconds on first run). The flag server waits for Oracle and Kafka to be healthy before starting. The sample consumer waits for the flag server to be healthy.

### Stop

```bash
docker compose down

# To also remove the Oracle data volume (full reset):
docker compose down -v
```

### Environment variables

The `docker-compose.yml` passes these to the flag server:

| Variable | Value | Purpose |
|----------|-------|---------|
| `SPRING_DATASOURCE_URL` | `jdbc:oracle:thin:@oracle:1521/XEPDB1` | Oracle connection |
| `SPRING_DATASOURCE_USERNAME` | `feature_flag` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | `feature_flag` | DB password |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` | Kafka broker |

And these to the sample consumer:

| Variable | Value | Purpose |
|----------|-------|---------|
| `FLAG_SERVER_URL` | `http://flag-server:8080` | SDK server URL |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` | Kafka broker |
| `FLAG_ENVIRONMENT` | `default` | Environment name for flag evaluation |

---

## 6. Using the Flag Server

All examples below assume the server is running at `http://localhost:8080`.

### 6.1 Creating a Flag

```bash
curl -s -X POST http://localhost:8080/api/flags \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-payment-gateway",
    "name": "New Payment Gateway",
    "description": "Switch from legacy to Stripe payment processing",
    "owner": "team-payments",
    "staleAfterDays": 90,
    "environments": ["default", "production"]
  }'
```

**Request fields:**

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `flagKey` | string | Yes | Only lowercase letters, numbers, and hyphens (`^[a-z0-9-]+$`) |
| `name` | string | Yes | Must not be blank |
| `description` | string | No | |
| `owner` | string | No | |
| `staleAfterDays` | integer | No | Days before the flag is considered stale (default: 90) |
| `environments` | string[] | No | Environment names to create (each starts disabled) |

**Response (201 Created):**

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "flagKey": "new-payment-gateway",
  "name": "New Payment Gateway",
  "description": "Switch from legacy to Stripe payment processing",
  "owner": "team-payments",
  "lifecycleState": "CREATED",
  "staleAfterDays": 90,
  "environments": [
    {
      "id": 1,
      "environment": "default",
      "enabled": false,
      "rolloutPercentage": null
    },
    {
      "id": 2,
      "environment": "production",
      "enabled": false,
      "rolloutPercentage": null
    }
  ],
  "createdAt": "2026-03-12T10:00:00Z",
  "updatedAt": "2026-03-12T10:00:00Z"
}
```

**Error -- duplicate key (409 Conflict):**

```json
{
  "status": 409,
  "message": "Flag with key 'new-payment-gateway' already exists",
  "timestamp": "2026-03-12T10:00:01Z"
}
```

### 6.2 Listing All Flags

```bash
curl -s http://localhost:8080/api/flags
```

**Response (200 OK):**

```json
[
  {
    "id": "a1b2c3d4-...",
    "flagKey": "new-payment-gateway",
    "name": "New Payment Gateway",
    "description": "Switch from legacy to Stripe payment processing",
    "owner": "team-payments",
    "lifecycleState": "CREATED",
    "staleAfterDays": 90,
    "environments": [...],
    "createdAt": "2026-03-12T10:00:00Z",
    "updatedAt": "2026-03-12T10:00:00Z"
  }
]
```

### 6.3 Getting a Single Flag

```bash
curl -s http://localhost:8080/api/flags/new-payment-gateway
```

Returns the same `FlagResponse` structure as above, for the flag with that key. Returns 404 if not found.

### 6.4 Updating a Flag

```bash
curl -s -X PUT http://localhost:8080/api/flags/new-payment-gateway \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Stripe Payment Gateway",
    "description": "Updated description",
    "owner": "team-payments-v2",
    "staleAfterDays": 60
  }'
```

**Request fields (all optional -- only provided fields are updated):**

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Display name |
| `description` | string | Description |
| `owner` | string | Owning team/person |
| `staleAfterDays` | integer | Staleness threshold |

The update is audited with per-field old/new diffs. If nothing changed, no audit entry is created.

### 6.5 Managing Flag Lifecycle

Flags follow a strict state machine: `CREATED` -> `ACTIVE` -> `RETIRED` -> `ARCHIVED`.

**Transition a flag to ACTIVE:**

```bash
curl -s -X POST http://localhost:8080/api/flags/new-payment-gateway/lifecycle \
  -H "Content-Type: application/json" \
  -d '{
    "targetState": "ACTIVE",
    "reason": "Ready for production rollout",
    "transitionedBy": "alice@example.com"
  }'
```

**Request fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `targetState` | string | Yes | One of: `ACTIVE`, `RETIRED`, `ARCHIVED` |
| `reason` | string | No | Why the transition is being made |
| `transitionedBy` | string | Yes | Who is making the transition |

**Response (200 OK):**

```json
{
  "flagId": "a1b2c3d4-...",
  "flagKey": "new-payment-gateway",
  "fromState": "CREATED",
  "toState": "ACTIVE",
  "reason": "Ready for production rollout",
  "transitionedBy": "alice@example.com",
  "transitionedAt": "2026-03-12T10:05:00Z"
}
```

**Invalid transitions return 400 Bad Request.** For example, you cannot go from CREATED directly to ARCHIVED -- you must follow the sequence CREATED -> ACTIVE -> RETIRED -> ARCHIVED.

**Get transition history:**

```bash
curl -s http://localhost:8080/api/flags/new-payment-gateway/lifecycle/history
```

Returns an array of `LifecycleTransitionResponse` objects in chronological order.

### 6.6 Toggling a Flag (Enabling/Disabling in an Environment)

This is the core operation. Toggling a flag in an environment triggers the two-phase activation protocol when healthy SDK instances are registered.

```bash
curl -s -X PUT http://localhost:8080/api/flags/new-payment-gateway/environments/default \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'
```

**Request fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `enabled` | boolean | Yes | `true` to enable, `false` to disable |

**Response when no healthy instances are registered (direct toggle):**

```json
{
  "id": null,
  "flagId": "a1b2c3d4-...",
  "environment": "default",
  "newEnabled": true,
  "status": "COMMITTED",
  "totalInstances": 0,
  "ackedInstances": 0,
  "timeoutSeconds": 60,
  "ackedInstanceIds": [],
  "createdAt": "2026-03-12T10:10:00Z",
  "completedAt": "2026-03-12T10:10:00Z"
}
```

**Response when healthy instances are registered (two-phase activation started):**

```json
{
  "id": "b2c3d4e5-...",
  "flagId": "a1b2c3d4-...",
  "environment": "default",
  "newEnabled": true,
  "status": "PREPARING",
  "totalInstances": 2,
  "ackedInstances": 0,
  "timeoutSeconds": 60,
  "ackedInstanceIds": [],
  "createdAt": "2026-03-12T10:10:00Z",
  "completedAt": null
}
```

### 6.7 Checking Activation Status

When a two-phase activation is in progress, you can check its status:

```bash
# Get a specific activation
curl -s http://localhost:8080/api/activations/b2c3d4e5-...

# List all pending activations
curl -s http://localhost:8080/api/activations/pending
```

**Activation statuses:** `PREPARING`, `COMMITTED`, `ROLLED_BACK`

**Response (200 OK):**

```json
{
  "id": "b2c3d4e5-...",
  "flagId": "a1b2c3d4-...",
  "environment": "default",
  "newEnabled": true,
  "status": "COMMITTED",
  "totalInstances": 2,
  "ackedInstances": 2,
  "timeoutSeconds": 60,
  "ackedInstanceIds": ["instance-1-uuid", "instance-2-uuid"],
  "createdAt": "2026-03-12T10:10:00Z",
  "completedAt": "2026-03-12T10:10:03Z"
}
```

### 6.8 Viewing the Audit Log

```bash
# Paginated audit log (all flags)
curl -s "http://localhost:8080/api/audit?page=0&size=20"

# Audit log for a specific flag (by flag ID, not flagKey)
curl -s http://localhost:8080/api/audit/flag/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Paginated response:**

```json
{
  "content": [
    {
      "id": 1,
      "flagId": "a1b2c3d4-...",
      "action": "CREATED",
      "oldValue": null,
      "newValue": "flagKey=new-payment-gateway; name=New Payment Gateway",
      "changedBy": "system",
      "metadata": null,
      "createdAt": "2026-03-12T10:00:00Z"
    },
    {
      "id": 2,
      "flagId": "a1b2c3d4-...",
      "action": "UPDATED",
      "oldValue": "name=New Payment Gateway; owner=team-payments",
      "newValue": "name=Stripe Payment Gateway; owner=team-payments-v2",
      "changedBy": "system",
      "metadata": null,
      "createdAt": "2026-03-12T10:03:00Z"
    },
    {
      "id": 3,
      "flagId": "a1b2c3d4-...",
      "action": "TOGGLED",
      "oldValue": "default=false",
      "newValue": "default=true",
      "changedBy": "system",
      "metadata": null,
      "createdAt": "2026-03-12T10:10:00Z"
    }
  ],
  "pageable": { ... },
  "totalElements": 3,
  "totalPages": 1,
  ...
}
```

### 6.9 Monitoring Registered Instances

```bash
# All registered instances
curl -s http://localhost:8080/api/instances

# Only healthy instances
curl -s http://localhost:8080/api/instances/healthy

# Specific instance
curl -s http://localhost:8080/api/instances/<instanceId>
```

**Response (200 OK):**

```json
[
  {
    "instanceId": "c3d4e5f6-...",
    "serviceName": "sample-consumer",
    "hostAddress": "172.18.0.5",
    "port": 8081,
    "healthStatus": "HEALTHY",
    "sdkVersion": "1.0.0-SNAPSHOT",
    "lastHeartbeat": "2026-03-12T10:15:00Z",
    "registeredAt": "2026-03-12T10:00:30Z"
  }
]
```

Instances that miss heartbeats for more than 45 seconds are automatically marked `UNHEALTHY` by the server's heartbeat monitor scheduler (runs every 15 seconds).

---

## 7. Using the Web Dashboard

The flag server includes a Thymeleaf-based web dashboard for full flag management. All pages are under the `/dashboard` prefix.

| URL | Page | Description |
|-----|------|-------------|
| `http://localhost:8080/dashboard` | Flag List | All flags with search, lifecycle filtering, and "New Flag" button |
| `http://localhost:8080/dashboard/flags/new` | Create Flag | Form to create a new feature flag |
| `http://localhost:8080/dashboard/flags/{flagKey}` | Flag Detail | Full flag details with edit, toggle, lifecycle, and archive actions |
| `http://localhost:8080/dashboard/flags/{flagKey}/edit` | Edit Flag | Form to edit flag name, description, owner, staleness |
| `http://localhost:8080/dashboard/instances` | Instance Registry | All registered SDK instances with health status counts |
| `http://localhost:8080/dashboard/activations` | Pending Activations | List of in-progress two-phase activations |
| `http://localhost:8080/dashboard/activations/{id}` | Activation Detail | Details of a specific activation |
| `http://localhost:8080/dashboard/audit` | Audit Log | Paginated audit trail with pagination controls |

### 7.1 Flag List Page

The flag list is the main entry point. It shows all flags in a table with key, name, owner, lifecycle state, environments, and last update time.

**Filtering:**
- `?lifecycle=ACTIVE` -- filter flags by lifecycle state (CREATED, ACTIVE, RETIRED, ARCHIVED)
- `?search=payment` -- search flags by key, name, or owner

Example: `http://localhost:8080/dashboard?lifecycle=ACTIVE&search=payment`

**Creating a flag:** Click the **"New Flag"** button in the top-right corner to navigate to the create form.

### 7.2 Creating a Flag

Navigate to `/dashboard/flags/new` (or click "New Flag" on the list page).

**Form fields:**
| Field | Required | Description |
|-------|----------|-------------|
| Flag Key | Yes | Unique identifier. Lowercase letters, numbers, and hyphens only (e.g. `new-payment-gateway`). |
| Name | Yes | Human-readable name (e.g. "New Payment Gateway"). |
| Description | No | Purpose/context for the flag. |
| Owner | No | Team or person responsible (e.g. `payments-team`). |
| Stale After (days) | No | Days before the flag is considered stale (default: 90). |
| Environments | No | Comma-separated list of environments to create (default: `default`). E.g. `default, staging, production`. |

On successful creation, you are redirected to the flag's detail page with a success message. Validation errors are shown inline and form values are preserved.

### 7.3 Flag Detail Page

The detail page (`/dashboard/flags/{flagKey}`) is the central hub for managing a single flag. It shows:

**Details card:** Flag metadata (name, owner, staleness threshold, creation/update timestamps, ID, description).

**Edit button:** Click "Edit" in the header (hidden for ARCHIVED flags) to modify the flag's name, description, owner, or staleness threshold. Changes are tracked in the audit log with field-level diffs.

**Lifecycle Transitions:** Context-aware action buttons based on the flag's current state:
| Current State | Available Transitions |
|---------------|----------------------|
| CREATED | Activate, Archive |
| ACTIVE | Retire, Archive |
| RETIRED | Re-activate, Archive |
| ARCHIVED | *(none -- terminal state)* |

Clicking a transition button immediately transitions the flag and shows a success/error flash message.

**Environment Toggles** *(ACTIVE flags only):* Each environment shows its current ON/OFF state with a toggle button. Clicking "Turn ON" or "Turn OFF":
- If no SDK instances are registered: the toggle takes effect immediately.
- If SDK instances are registered: a two-phase activation is initiated (PREPARE -> ACK -> COMMIT). A flash message indicates the activation is in progress.

**Add Environment** *(ACTIVE or CREATED flags):* An inline form at the bottom of the environments section lets you add a new environment (created in OFF state).

**Archive (Danger Zone)** *(non-ARCHIVED flags):* A red "Archive Flag" button at the bottom of the page. Requires confirmation via browser dialog. This performs a soft delete by transitioning the flag to ARCHIVED state.

### 7.4 Editing a Flag

Navigate to `/dashboard/flags/{flagKey}/edit` (or click "Edit" on the detail page).

The edit form is pre-filled with the flag's current values. The flag key is displayed but cannot be changed. Fields you can update:
- **Name** -- the display name
- **Description** -- purpose/context
- **Owner** -- responsible team or person
- **Stale After (days)** -- staleness threshold

Changes are audited with per-field old/new diffs in the audit log.

### 7.5 Flash Messages

All dashboard mutations (create, edit, toggle, lifecycle, add environment, archive) display flash messages:
- **Green (success):** Operation completed successfully.
- **Red (error):** Operation failed with an error description.

Flash messages appear at the top of the page after redirect and disappear on the next page load.

### 7.6 Audit Log Page

Supports pagination:
- `?page=0&size=25` -- page number (zero-based) and page size

---

## 8. Integrating the SDK into Your Service

### 8.1 Add the Maven Dependency

```xml
<dependency>
    <groupId>com.paymentplatform</groupId>
    <artifactId>feature-flag-sdk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Since this is a multi-module project, the SDK is already available in your local Maven repository after running `mvn install` from the root.

### 8.2 Configuration Properties

Add the following to your service's `application.yml`:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.paymentplatform.*

feature-flag:
  server-url: ${FLAG_SERVER_URL:http://localhost:8080}    # REQUIRED -- triggers auto-configuration
  service-name: my-service                                 # Name shown in instance registry
  environment: ${FLAG_ENVIRONMENT:default}                 # Environment to evaluate flags against
  heartbeat-interval-seconds: 15                           # Heartbeat interval (default: 15)
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    topic: feature-flag-events                             # Must match server's topic
```

The `feature-flag.server-url` property is **required** -- the entire SDK auto-configuration is conditional on it being set. When absent, no SDK beans are created.

The SDK auto-generates a unique `instanceId` (UUID) on each startup. You can override it:

```yaml
feature-flag:
  instance-id: my-custom-instance-id
```

### 8.3 Integration Patterns

The SDK provides four declarative patterns and a low-level API. Here is each one with a full code example.

#### Pattern 1: Strategy Pattern (`@FeatureFlagEnabled` / `@FeatureFlagDisabled`)

Use this when you have an interface with two implementations and want the SDK to dynamically route to one or the other based on a flag.

**Define the interface:**

```java
public interface PaymentGateway {
    PaymentResult charge(String orderId, long amountCents);
    String gatewayName();
}
```

**Annotate the implementations:**

```java
@Component
@FeatureFlagDisabled("new-payment-gateway")  // Used when flag is OFF
public class LegacyPaymentGateway implements PaymentGateway {
    @Override
    public PaymentResult charge(String orderId, long amountCents) {
        String txnId = "LEGACY-" + UUID.randomUUID().toString().substring(0, 8);
        return new PaymentResult(orderId, amountCents, "legacy", txnId, true);
    }

    @Override
    public String gatewayName() {
        return "legacy";
    }
}

@Component
@FeatureFlagEnabled("new-payment-gateway")  // Used when flag is ON
public class StripePaymentGateway implements PaymentGateway {
    @Override
    public PaymentResult charge(String orderId, long amountCents) {
        String txnId = "STRIPE-" + UUID.randomUUID().toString().substring(0, 8);
        return new PaymentResult(orderId, amountCents, "stripe", txnId, true);
    }

    @Override
    public String gatewayName() {
        return "stripe";
    }
}
```

**Inject the interface normally:**

```java
@Service
public class PaymentService {
    private final PaymentGateway paymentGateway;  // SDK creates a dynamic proxy

    public PaymentService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public PaymentResult processPayment(String orderId, long amountCents) {
        // Automatically routes to Legacy or Stripe based on "new-payment-gateway" flag
        return paymentGateway.charge(orderId, amountCents);
    }
}
```

The SDK creates a JDK dynamic proxy that checks the flag at each method invocation and delegates to the correct implementation. Both `@FeatureFlagEnabled` and `@FeatureFlagDisabled` must reference the same flag key and the same interface.

#### Pattern 2: AOP Pattern (`@FeatureToggle`)

Use this on a method to conditionally execute it or fall back to a different method based on a flag.

```java
@Service
public class PaymentService {

    @FeatureToggle(flag = "new-fee-calculation", fallbackMethod = "legacyCalculateFee")
    public long calculateFee(long amountCents) {
        // New fee: 2.5% with minimum 50 cents -- runs when flag is ON
        long fee = BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(0.025))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
        return Math.max(fee, 50);
    }

    // Fallback -- runs when flag is OFF. Must have the same parameter types.
    public long legacyCalculateFee(long amountCents) {
        // Legacy fee: flat 3%
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(0.03))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }
}
```

The `@FeatureToggle` annotation attributes:

| Attribute | Type | Description |
|-----------|------|-------------|
| `flag` | String | The flag key to check |
| `fallbackMethod` | String | Method name in the same class. Must accept the same parameters. Can be private. |

When the flag is enabled, the annotated method runs normally. When disabled, the fallback method is invoked instead. The aspect uses `getDeclaredMethod` with `setAccessible(true)`, so the fallback can be private.

#### Pattern 3: Configuration Pattern (`@FeatureFlagConfiguration`)

Use this to conditionally activate an entire Spring `@Configuration` class (and all its beans) based on a flag. This is evaluated at **bean definition time** using Spring's `@Conditional` mechanism.

```java
@Configuration
@FeatureFlagConfiguration("beta-logging")
public class BetaFeatureConfig {

    @Bean
    public BetaLoggingEnhancer betaLoggingEnhancer() {
        return new BetaLoggingEnhancer();
    }

    public static class BetaLoggingEnhancer {
        public BetaLoggingEnhancer() {
            // This only runs if "beta-logging" flag is enabled at startup
        }

        public void logPaymentEvent(String orderId, long amountCents, String event) {
            // Enhanced logging
        }
    }
}
```

This pattern evaluates the flag from properties at startup:

```yaml
feature-flag:
  flags:
    beta-logging:
      enabled: true   # Set this to control the @FeatureFlagConfiguration condition
```

Note: Because this is evaluated at bean definition time (not runtime), it reads from `feature-flag.flags.{flagKey}.enabled` in the application properties. It does NOT check the flag server at startup. This is for static feature flags that control bean wiring.

#### Pattern 4: Route Gating (combining `@FeatureToggle` on a controller)

Use `@FeatureToggle` on a controller method to gate an entire endpoint behind a flag:

```java
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    @GetMapping("/beta/status")
    @FeatureToggle(flag = "beta-payments-api", fallbackMethod = "betaStatusUnavailable")
    public Map<String, Object> betaStatus() {
        return Map.of(
                "beta", true,
                "message", "Beta payments API is active"
        );
    }

    private Map<String, Object> betaStatusUnavailable() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Beta payments API is not available");
    }
}
```

When `beta-payments-api` is OFF, the endpoint returns 404. When ON, it returns the beta status.

### 8.4 Low-Level API

For programmatic flag checks, inject `FeatureFlagService`:

```java
@Service
public class MyService {

    private final FeatureFlagService featureFlagService;

    public MyService(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    public void doSomething() {
        // Check flag for the configured default environment
        if (featureFlagService.isEnabled("my-flag")) {
            // new behavior
        } else {
            // old behavior
        }

        // Check flag for a specific environment
        if (featureFlagService.isEnabled("my-flag", "production")) {
            // production-specific behavior
        }
    }
}
```

### 8.5 Request Pinning

The SDK automatically registers a servlet filter (`FeatureFlagRequestFilter`) that snapshots all flag values into a `ThreadLocal` at the start of each HTTP request. All calls to `featureFlagService.isEnabled(...)` during that request see the same flag values, even if a flag changes mid-request. The snapshot is cleared in the `finally` block after the request completes.

This is automatic -- you do not need to configure anything. It ensures that a single HTTP request always sees a consistent view of all flags.

---

## 9. Understanding Two-Phase Activation

When you toggle a flag and healthy SDK instances are registered with the server, the system uses a two-phase activation protocol to ensure consistent flag state across all instances.

### Timeline

```
Time    Server                          Kafka                    SDK Instances
-----   ------                          -----                    -------------
T+0     toggleFlag() called
        Creates PendingActivation
        (status=PREPARING)
        Publishes FLAG_PREPARE -------> FLAG_PREPARE event -----> All instances
                                                                  receive event

T+1                                                               Instance stores
                                                                  pending state
                                                                  Sends POST /ack

T+2     Receives ACK from Instance 1
        (pessimistic lock on row)
        ackedInstances: 1/2

T+3     Receives ACK from Instance 2
        ackedInstances: 2/2
        All ACKs received!
        Publishes FLAG_COMMIT --------> FLAG_COMMIT event ------> All instances
        Updates flag in DB                                        apply new state
        (status=COMMITTED)

T+60    If not all ACKs received:
        ActivationTimeoutScheduler
        triggers rollback
        Publishes FLAG_ROLLBACK ------> FLAG_ROLLBACK event ----> All instances
        (status=ROLLED_BACK)                                      revert to old state
```

### Key details

- **Pessimistic locking**: The server acquires a `PESSIMISTIC_WRITE` lock on the `PendingActivation` row when processing each ACK, preventing race conditions when multiple ACKs arrive simultaneously.
- **Timeout**: If not all instances ACK within the configured timeout (default 60 seconds), the activation is automatically rolled back. The `ActivationTimeoutScheduler` polls every 5 seconds.
- **No healthy instances**: If no healthy SDK instances are registered when a toggle is requested, the flag is changed directly without the two-phase protocol.
- **Broadcast semantics**: Each SDK instance uses a unique Kafka consumer group (`feature-flag-{instanceId}`), so every instance receives every event.

---

## 10. Testing

### Unit Tests

Unit tests use Mockito and do not require Docker or any running services.

```bash
# Server unit tests
mvn test -pl feature-flag-server -Dtest="*Test" -Dtest="!*IntegrationTest"

# SDK unit tests
mvn test -pl feature-flag-sdk
```

### Integration Tests

Integration tests in `feature-flag-server` use Spring Boot's `@SpringBootTest` with Testcontainers. They start an Oracle 21c XE container (`gvenzl/oracle-xe:21-slim`) automatically. Docker must be running.

```bash
# Run all tests including integration
mvn test -pl feature-flag-server
```

Integration tests disable Kafka via:
```
spring.autoconfigure.exclude=KafkaAutoConfiguration
```

This means integration tests validate the database layer, service logic, and REST API without needing a Kafka broker.

### What the test suites cover

- **FlagServiceTest**: Flag CRUD, toggle logic, audit generation, per-field diff tracking
- **FlagLifecycleServiceTest**: State machine transitions, invalid transition rejection
- **TwoPhaseActivationServiceTest**: ACK processing, commit/rollback logic, timeout behavior
- **InstanceRegistryServiceTest**: Registration, heartbeat, deregistration, health monitoring
- **SDK tests**: Cache behavior, proxy routing, AOP aspect, lifecycle management

---

## 11. Running the Load Test

The `load-test.sh` script tests the two-phase activation protocol under load with multiple consumer instances.

### Prerequisites

- Docker Compose must be running (`docker compose up -d`)
- All services must be healthy
- The script uses the Docker network to spin up additional consumer containers

### Run

```bash
chmod +x load-test.sh
./load-test.sh
```

### What it does

1. Starts 3 additional sample-consumer containers on the Docker network
2. Waits for all instances to register and show as healthy
3. Creates test flags
4. Fires 5 concurrent toggle requests
5. Verifies that all activations complete with COMMITTED status (all instances ACKed)
6. Cleans up the extra containers
7. Reports pass/fail counts

---

## 12. Running the E2E Test

The `e2e-test.sh` script is a comprehensive end-to-end test that exercises the full system.

### Prerequisites

- Docker Compose must be running with all services healthy
- No pre-existing flags (or start fresh with `docker compose down -v && docker compose up -d`)

### Run

```bash
chmod +x e2e-test.sh
./e2e-test.sh
```

### What it tests

1. Creates `new-payment-gateway` and `new-fee-calculation` flags with `default` environment
2. Verifies flags appear in the list
3. Checks the sample consumer's payment status endpoint
4. Toggles flags on and verifies the consumer switches behavior (gateway, fee calculation)
5. Toggles flags off and verifies the consumer reverts
6. Reports pass/fail counts

---

## 13. Configuration Reference

### Flag Server Properties (`feature-flag-server/src/main/resources/application.yml`)

| Property | Default | Description |
|----------|---------|-------------|
| `spring.datasource.url` | `jdbc:oracle:thin:@localhost:1521/XEPDB1` | Oracle database JDBC URL |
| `spring.datasource.username` | `feature_flag` | Database username |
| `spring.datasource.password` | `feature_flag` | Database password |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker address |
| `server.port` | `8080` | HTTP server port |
| `feature-flag.activation-timeout-seconds` | `60` | Seconds before a pending activation times out and rolls back |
| `feature-flag.activation-timeout-check-interval-ms` | `5000` | How often (ms) the timeout scheduler checks for expired activations |
| `feature-flag.heartbeat-interval-seconds` | `15` | Expected heartbeat interval from SDK instances |
| `feature-flag.unhealthy-threshold-seconds` | `45` | Seconds of heartbeat silence before an instance is marked UNHEALTHY |
| `feature-flag.staleness-check-cron` | `0 0 2 * * *` | Cron expression for the nightly staleness check (default: 2:00 AM daily) |
| `feature-flag.default-stale-after-days` | `90` | Default days before a flag is considered stale |
| `feature-flag.kafka.topic` | `feature-flag-events` | Kafka topic for flag events |
| `feature-flag.kafka.partitions` | `3` | Number of partitions for the topic |
| `feature-flag.kafka.replication-factor` | `1` | Replication factor for the topic |

### SDK Properties (consumer service's `application.yml`)

| Property | Default | Description |
|----------|---------|-------------|
| `feature-flag.server-url` | *(none -- required)* | URL of the flag server. Auto-configuration is conditional on this being set. |
| `feature-flag.service-name` | *(none)* | Service name shown in the instance registry |
| `feature-flag.environment` | `default` | Default environment for flag evaluation |
| `feature-flag.instance-id` | *(auto-generated UUID)* | Unique identifier for this SDK instance |
| `feature-flag.heartbeat-interval-seconds` | `15` | How often (seconds) to send heartbeats to the server |
| `feature-flag.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker address for the SDK consumer |
| `feature-flag.kafka.topic` | `feature-flag-events` | Kafka topic to consume flag events from |
| `feature-flag.flags.{flagKey}.enabled` | *(none)* | Static flag override for `@FeatureFlagConfiguration` conditions (evaluated at bean definition time) |

---

## 14. Project Structure

```
featureFlag/
|-- pom.xml                          # Parent POM (Java 17, Spring Boot 3.2.5)
|-- docker-compose.yml               # Oracle, Zookeeper, Kafka, Flag Server, Sample Consumer
|-- e2e-test.sh                      # End-to-end test script
|-- load-test.sh                     # Load test with multiple consumer instances
|-- how-to-use.md                    # This file
|-- CLAUDE.md                        # AI assistant instructions
|
|-- docs/
|   |-- rest-api.md                  # REST API reference
|   |-- sdk-integration-guide.md     # SDK integration patterns
|   |-- flag-lifecycle.md            # Lifecycle state machine details
|   |-- two-phase-activation.md      # Two-phase activation protocol details
|   |-- operations-runbook.md        # Operations and deployment guide
|
|-- feature-flag-server/             # Spring Boot server application
|   |-- pom.xml
|   |-- Dockerfile
|   |-- src/main/java/com/paymentplatform/flagserver/
|   |   |-- FlagServerApplication.java
|   |   |-- config/                  # Spring configuration (Kafka topic creation, etc.)
|   |   |-- controller/              # REST controllers
|   |   |   |-- FlagController.java            # /api/flags -- CRUD, toggle, lifecycle
|   |   |   |-- AuditController.java           # /api/audit -- audit log queries
|   |   |   |-- InstanceRegistryController.java # /api/instances -- registration, heartbeat
|   |   |   |-- ActivationController.java      # /api/activations -- ACK, status
|   |   |   |-- DashboardController.java       # /dashboard -- Thymeleaf web UI
|   |   |   |-- GlobalExceptionHandler.java    # Centralized error handling
|   |   |-- dto/                     # Request/response records (Java records with Jakarta validation)
|   |   |-- entity/                  # JPA entities (Flag, FlagEnvironment, FlagAuditLog, etc.)
|   |   |   |-- enums/              # LifecycleState, HealthStatus, ActivationStatus
|   |   |-- event/                   # KafkaEventPublisher, FlagEvent record
|   |   |-- exception/              # Custom exceptions (FlagNotFoundException, etc.)
|   |   |-- repository/             # Spring Data JPA repositories
|   |   |-- scheduler/              # HeartbeatMonitorScheduler, ActivationTimeoutScheduler
|   |   |-- service/                # Business logic (FlagService, TwoPhaseActivationService, etc.)
|   |-- src/main/resources/
|   |   |-- application.yml          # Server configuration
|   |   |-- db/changelog/            # Liquibase migration YAML files
|   |   |-- templates/               # Thymeleaf HTML templates
|
|-- feature-flag-sdk/                # SDK library JAR
|   |-- pom.xml
|   |-- src/main/java/com/paymentplatform/flagsdk/
|   |   |-- FeatureFlagService.java  # Main API: isEnabled(flagKey), isEnabled(flagKey, env)
|   |   |-- annotation/              # @FeatureFlagEnabled, @FeatureFlagDisabled, @FeatureToggle, @FeatureFlagConfiguration
|   |   |-- aop/                     # FeatureToggleAspect (AOP around advice)
|   |   |-- cache/                   # FlagCacheManager (Caffeine-backed)
|   |   |-- client/                  # FlagServerClient (REST client using RestTemplate)
|   |   |-- condition/               # FeatureFlagCondition (Spring @Conditional evaluator)
|   |   |-- config/                  # FeatureFlagAutoConfiguration, FeatureFlagProperties
|   |   |-- event/                   # FlagEvent, FlagEventListener (Kafka consumer)
|   |   |-- filter/                  # FeatureFlagRequestFilter (request-scoped flag pinning)
|   |   |-- heartbeat/              # HeartbeatManager (periodic heartbeats)
|   |   |-- lifecycle/              # SdkLifecycleManager (startup: register + sync, shutdown: deregister)
|   |   |-- proxy/                  # FeatureFlagProxyFactory, FeatureFlagProxyBeanRegistrar
|   |-- src/main/resources/META-INF/spring/
|   |   |-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
|
|-- feature-flag-sample-consumer/    # Demo app using the SDK
|   |-- pom.xml
|   |-- Dockerfile
|   |-- src/main/java/com/paymentplatform/sample/
|   |   |-- SampleConsumerApplication.java
|   |   |-- config/                  # BetaFeatureConfig (@FeatureFlagConfiguration example)
|   |   |-- controller/             # PaymentController (REST endpoints)
|   |   |-- gateway/                # PaymentGateway interface + Legacy/Stripe implementations
|   |   |-- service/                # PaymentService (@FeatureToggle example)
|   |-- src/main/resources/
|       |-- application.yml          # SDK configuration
```

---

## 15. Troubleshooting

### Oracle container takes too long to start

The Oracle XE 21 image can take 2-3 minutes on first run (it initializes the database). Subsequent starts are faster because the data volume is persisted.

```bash
# Check Oracle health
docker compose logs oracle | tail -20
# Look for "DATABASE IS READY TO USE"
```

### Flag server fails to start -- "Connection refused" to Oracle

Oracle is not ready yet. The docker-compose health check ensures the server waits, but if running outside Docker:

```bash
# Verify Oracle is accepting connections
docker compose exec oracle healthcheck.sh
```

### "Flag with key '...' already exists" (409 Conflict)

Flag keys are unique. If you get this during testing, either use a different key or reset the database:

```bash
docker compose down -v
docker compose up -d
```

### SDK not connecting to the flag server

Verify that `feature-flag.server-url` is set in your `application.yml`. The entire SDK auto-configuration is conditional on this property. Without it, no SDK beans are created and no connection is attempted.

```yaml
feature-flag:
  server-url: http://localhost:8080  # Must be set!
```

### Toggle returns COMMITTED immediately (no two-phase activation)

This is expected when no healthy SDK instances are registered. The server checks for healthy instances and, if none exist, applies the toggle directly. Start a consumer service with the SDK to see the two-phase protocol in action.

### Activation stuck in PREPARING / timed out

This means one or more SDK instances did not ACK within the timeout (default 60 seconds). Possible causes:
- SDK instance crashed after receiving PREPARE but before sending ACK
- Kafka consumer lag (message not yet delivered)
- Network issue between SDK instance and flag server

The `ActivationTimeoutScheduler` automatically rolls back stuck activations. Check the audit log and instance health:

```bash
# Check instance health
curl -s http://localhost:8080/api/instances

# Check pending activations
curl -s http://localhost:8080/api/activations/pending
```

### Port already in use

If ports 1521, 8080, 8081, 9092, or 2181 are in use, stop the conflicting processes or change the port mappings in `docker-compose.yml`.

```bash
# Find what is using a port (macOS)
lsof -i :8080
```

### Tests fail with "Could not connect to Oracle"

Integration tests use Testcontainers, which requires Docker to be running. Make sure Docker Desktop (or Docker Engine) is started:

```bash
docker info
```

### Kafka consumer not receiving events

Verify the topic name matches between server and SDK:
- Server: `feature-flag.kafka.topic` in `feature-flag-server/src/main/resources/application.yml`
- SDK: `feature-flag.kafka.topic` in your consumer's `application.yml`

Both default to `feature-flag-events`.

Also check that Kafka's `bootstrap-servers` is correct. Inside Docker Compose, services use `kafka:29092`. Outside Docker, use `localhost:9092`.

---

## 16. Further Reading

Detailed reference documentation is in the `docs/` directory:

- **[REST API Reference](docs/rest-api.md)** -- Complete API documentation with all endpoints, request/response schemas, and error codes
- **[SDK Integration Guide](docs/sdk-integration-guide.md)** -- Deep dive into all four SDK integration patterns, request pinning, and SDK lifecycle
- **[Flag Lifecycle](docs/flag-lifecycle.md)** -- State machine diagram, valid transitions, and staleness detection
- **[Two-Phase Activation](docs/two-phase-activation.md)** -- Protocol flow diagram, pessimistic locking, timeout/rollback behavior
- **[Operations Runbook](docs/operations-runbook.md)** -- Production deployment, monitoring, and operational procedures
