# Feature Flag SDK Integration Guide

This guide covers how to integrate the Feature Flag SDK into a Spring Boot consumer service. The SDK provides automatic flag synchronization, real-time updates via Kafka, request-scoped flag pinning, and multiple declarative integration patterns.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Maven Dependency Setup](#maven-dependency-setup)
- [Configuration Properties](#configuration-properties)
- [Integration Patterns](#integration-patterns)
  - [1. Strategy Pattern](#1-strategy-pattern)
  - [2. AOP Pattern](#2-aop-pattern)
  - [3. Configuration Pattern](#3-configuration-pattern)
  - [4. Route Gating Pattern](#4-route-gating-pattern)
- [Low-Level API](#low-level-api)
- [Request Pinning](#request-pinning)
- [Two-Phase Activation](#two-phase-activation)
- [SDK Lifecycle](#sdk-lifecycle)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

- Java 17+
- Spring Boot 3.2.x
- A running Feature Flag Server instance
- Apache Kafka (for real-time flag updates and two-phase activation)

## Maven Dependency Setup

Add the SDK dependency to your service's `pom.xml`:

```xml
<dependency>
    <groupId>com.paymentplatform</groupId>
    <artifactId>feature-flag-sdk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The SDK transitively brings in:
- `spring-boot-starter-web` (RestTemplate for server communication)
- `spring-boot-starter-aop` (for `@FeatureToggle` aspect)
- `spring-kafka` (for real-time event consumption)
- `caffeine` (for local flag caching)

No `@Enable*` annotation is needed on your application class. The SDK uses Spring Boot auto-configuration, activated automatically when the `feature-flag.server-url` property is set.

## Configuration Properties

Add the following to your `application.yml`:

```yaml
feature-flag:
  server-url: ${FLAG_SERVER_URL:http://localhost:8080}
  service-name: my-payment-service
  environment: ${FLAG_ENVIRONMENT:default}
  heartbeat-interval-seconds: 15
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    topic: feature-flag-events
```

You also need standard Spring Kafka consumer configuration:

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
```

### Property Reference

| Property | Required | Default | Description |
|---|---|---|---|
| `feature-flag.server-url` | Yes | -- | Base URL of the Feature Flag Server. This property activates the entire SDK. |
| `feature-flag.service-name` | Yes | -- | Identifies your service during instance registration. |
| `feature-flag.environment` | No | `default` | Environment to evaluate flags against (e.g., `production`, `staging`). |
| `feature-flag.instance-id` | No | Random UUID | Unique identifier for this running instance. Auto-generated if omitted. |
| `feature-flag.heartbeat-interval-seconds` | No | `15` | How often (in seconds) the SDK sends heartbeats to the server. The server marks instances unhealthy after 45s of silence. |
| `feature-flag.kafka.bootstrap-servers` | No | `localhost:9092` | Kafka broker addresses for real-time flag events. |
| `feature-flag.kafka.topic` | No | `feature-flag-events` | Kafka topic for flag change events. |

### Static Flag Overrides (for Configuration Pattern)

For the `@FeatureFlagConfiguration` annotation (evaluated at bean definition time), you can set flag values as static properties:

```yaml
feature-flag:
  flags:
    beta-logging:
      enabled: true
```

This is required because `@FeatureFlagConfiguration` uses Spring's `@Conditional` mechanism, which runs before the application context is fully initialized and before the SDK can connect to the server.

---

## Integration Patterns

### 1. Strategy Pattern

**Use when:** You have two implementations of the same interface and want to switch between them based on a flag at runtime.

The SDK creates a JDK dynamic proxy that routes method calls to the correct implementation based on the flag's current state.

#### Step 1: Define an interface

```java
public interface PaymentGateway {
    PaymentResult charge(String orderId, long amountCents);
    String gatewayName();
}
```

#### Step 2: Annotate implementations

Mark the legacy implementation with `@FeatureFlagDisabled` and the new implementation with `@FeatureFlagEnabled`, both referencing the same flag key:

```java
@Component
@FeatureFlagDisabled("new-payment-gateway")
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
```

```java
@Component
@FeatureFlagEnabled("new-payment-gateway")
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

#### Step 3: Inject the interface

The SDK's `FeatureFlagProxyBeanRegistrar` automatically detects the annotated pair and registers a proxy bean. Inject the interface as usual:

```java
@Service
public class PaymentService {

    private final PaymentGateway paymentGateway;

    public PaymentService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public PaymentResult processPayment(String orderId, long amountCents) {
        // Automatically routes to LegacyPaymentGateway or StripePaymentGateway
        // based on the current state of the "new-payment-gateway" flag
        return paymentGateway.charge(orderId, amountCents);
    }
}
```

**Key points:**
- Both implementations must implement the same interface.
- Both must be Spring `@Component` beans.
- Both must reference the same flag key.
- Routing is evaluated on every method call, so flag changes take effect immediately (subject to request pinning).

---

### 2. AOP Pattern

**Use when:** You want to toggle behavior at the method level -- run one method when the flag is on, fall back to another when the flag is off.

#### Basic usage

Annotate the "new" method with `@FeatureToggle` and provide a `fallbackMethod` that has the same parameter types and return type:

```java
@Service
public class PaymentService {

    @FeatureToggle(flag = "new-fee-calculation", fallbackMethod = "legacyCalculateFee")
    public long calculateFee(long amountCents) {
        // New fee: 2.5% with minimum 50 cents
        long fee = BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(0.025))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
        return Math.max(fee, 50);
    }

    public long legacyCalculateFee(long amountCents) {
        // Legacy fee: flat 3%
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(0.03))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }
}
```

**How it works:**
- When `new-fee-calculation` is **enabled**: `calculateFee()` runs normally.
- When `new-fee-calculation` is **disabled**: the AOP aspect intercepts the call and invokes `legacyCalculateFee()` instead.

**Key points:**
- The fallback method must be in the same class.
- The fallback method must have the same parameter signature and return type.
- The fallback method can be `private` -- the aspect uses `getDeclaredMethod` + `setAccessible(true)`.
- Works on any Spring-managed bean (services, controllers, etc.).

---

### 3. Configuration Pattern

**Use when:** You want to conditionally activate an entire Spring `@Configuration` class (and all its beans) based on a flag.

```java
@Configuration
@FeatureFlagConfiguration("beta-logging")
public class BetaFeatureConfig {

    @Bean
    public BetaLoggingEnhancer betaLoggingEnhancer() {
        return new BetaLoggingEnhancer();
    }

    public static class BetaLoggingEnhancer {

        private static final Logger log = LoggerFactory.getLogger(BetaLoggingEnhancer.class);

        public BetaLoggingEnhancer() {
            log.info("Beta logging enhancer activated");
        }

        public void logPaymentEvent(String orderId, long amountCents, String event) {
            log.info("[BETA-LOG] orderId={} amount={} event={}", orderId, amountCents, event);
        }
    }
}
```

**Important caveat:** `@FeatureFlagConfiguration` uses Spring's `@Conditional` mechanism, which is evaluated at **bean definition time** (during application startup). This means:

- The flag value is read from the Spring `Environment` (i.e., `application.yml` properties), **not** from the Feature Flag Server.
- The flag state is fixed for the lifetime of the application context.
- To use this pattern, you must set a static property:

```yaml
feature-flag:
  flags:
    beta-logging:
      enabled: true
```

This pattern is best suited for features that require bean-graph-level activation (e.g., registering additional interceptors, scheduled jobs, or integration beans) where a restart is acceptable to change the flag.

---

### 4. Route Gating Pattern

**Use when:** You want to make an entire REST endpoint available or unavailable based on a flag, returning a 404 when the flag is off.

This is a specific application of the AOP pattern applied to controller methods:

```java
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    /**
     * This endpoint is only accessible when "beta-payments-api" is enabled.
     * When the flag is off, the fallback throws a 404.
     */
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

**How it works:**
- Flag **enabled**: the endpoint responds normally with beta status information.
- Flag **disabled**: the fallback method runs and throws a `ResponseStatusException` with a 404 status, making the endpoint effectively invisible.

This pattern works for any HTTP method (`GET`, `POST`, `PUT`, `DELETE`). The fallback must match the return type of the gated method.

---

## Low-Level API

For cases where the declarative annotations do not fit, inject `FeatureFlagService` directly:

```java
@Service
public class PaymentService {

    private final FeatureFlagService featureFlagService;

    public PaymentService(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    public void processPayment(String orderId, long amountCents) {
        if (featureFlagService.isEnabled("new-payment-gateway")) {
            // new path
        } else {
            // legacy path
        }
    }

    public boolean checkFlagForEnvironment(String flagKey, String env) {
        // Check a flag for a specific environment (overrides the configured default)
        return featureFlagService.isEnabled(flagKey, env);
    }
}
```

### API Reference

| Method | Description |
|---|---|
| `isEnabled(String flagKey)` | Returns `true` if the flag is enabled for the configured default environment. |
| `isEnabled(String flagKey, String environment)` | Returns `true` if the flag is enabled for the specified environment. |

Both methods respect request pinning (see below). If a pinned snapshot exists for the current request thread, the pinned value is returned instead of the live cache value.

---

## Request Pinning

Request pinning is a critical safety mechanism for payment processing. It ensures that **flag values remain consistent for the entire duration of an HTTP request**, even if a flag changes mid-request.

### How it works

1. The SDK registers a servlet filter (`FeatureFlagRequestFilter`) at a very high priority (`Integer.MIN_VALUE + 10`).
2. At the start of every HTTP request, the filter takes an immutable snapshot of all current flag values and stores it in a `ThreadLocal`.
3. All calls to `FeatureFlagService.isEnabled()` during that request return values from the snapshot.
4. When the request completes (in a `finally` block), the `ThreadLocal` is cleared.

### Why this matters for payments

Consider a payment flow that checks a flag twice:

```java
public PaymentResult processPayment(String orderId, long amount) {
    long fee = calculateFee(amount);      // checks "new-fee-calculation" flag
    return gateway.charge(orderId, amount + fee);  // checks "new-payment-gateway" flag
}
```

Without request pinning, a flag toggle arriving between these two calls could result in:
- A new fee calculation paired with the legacy gateway (or vice versa)
- Inconsistent audit records
- Potential financial discrepancies

With request pinning, both checks see the same flag state, guaranteeing consistency within the request.

### Non-request contexts

Request pinning only applies within HTTP request threads. For background jobs, scheduled tasks, or Kafka consumers, `FeatureFlagService.isEnabled()` reads directly from the live cache. If you need consistency in these contexts, snapshot the flag values yourself at the start of your processing unit.

---

## Two-Phase Activation

The SDK participates in a two-phase commit protocol when flag toggles are initiated from the server. This prevents partial rollouts where some instances see the new flag state while others still see the old one.

### Consumer-side flow

From the consumer's perspective, the process is fully automatic and requires no application code:

1. **PREPARE phase**: The server publishes a `FLAG_PREPARE` event to Kafka. The SDK's `FlagEventListener` receives it, stores the pending new value in the cache (without applying it), and sends an ACK back to the server via REST.

2. **Waiting for consensus**: The server waits for ACKs from all healthy registered instances. No flag values change during this window.

3. **COMMIT or ROLLBACK**:
   - If all instances ACK within the timeout (default 60 seconds): the server sends `FLAG_COMMIT`. The SDK applies the pending value, and the flag is now toggled.
   - If any instance fails to ACK in time: the server sends `FLAG_ROLLBACK`. The SDK discards the pending value, and the flag remains unchanged.

### What this means for your service

- **No code changes needed.** The SDK handles PREPARE/ACK/COMMIT/ROLLBACK automatically.
- **Atomic visibility.** All instances of your service see the flag change at approximately the same time (when COMMIT arrives).
- **Safe rollback.** If your instance is unhealthy or unreachable, the flag toggle is rolled back across all instances rather than applied partially.
- **Direct toggle fallback.** If no healthy instances are registered with the server, the flag toggles immediately without the two-phase protocol.

### Timeouts

The server-side activation timeout defaults to 60 seconds. If your service instances are slow to ACK (e.g., under heavy load), the activation will be rolled back. Monitor your logs for `FLAG_ROLLBACK` events.

### Kafka consumer group

Each SDK instance uses a unique Kafka consumer group (`feature-flag-{instanceId}`) to ensure **broadcast semantics** -- every instance receives every flag event. This is different from typical Kafka usage where consumers in the same group share partitions.

---

## SDK Lifecycle

Understanding the SDK lifecycle helps with debugging startup issues.

### Startup sequence

1. **Auto-configuration activates** when `feature-flag.server-url` is present.
2. **Beans are created**: cache manager, server client, Kafka consumer, heartbeat manager, request filter, AOP aspect, proxy factory.
3. **`SdkLifecycleManager.start()`** runs (via Spring's `SmartLifecycle`):
   - Registers this instance with the Feature Flag Server (POST to `/api/instances`).
   - Performs a full flag sync (GET from server), populating the local Caffeine cache.
4. **Kafka listener starts** consuming from `feature-flag-events` topic for real-time updates.
5. **Heartbeat timer starts** sending heartbeats every 15 seconds.

### Shutdown sequence

1. **`SdkLifecycleManager.stop()`** runs:
   - Deregisters this instance from the Feature Flag Server.
2. Kafka consumer and heartbeat timer are stopped by Spring's lifecycle management.

### Resilience

- If the server is unreachable at startup, registration and sync will fail, but the application will still start. Flags will default to `false` until the cache is populated.
- If the server becomes unreachable after startup, the local Caffeine cache continues serving the last-known flag values. Kafka events will resume updating the cache when connectivity is restored.
- Heartbeats keep the server informed that this instance is alive. If heartbeats stop for 45 seconds, the server marks the instance as unhealthy and excludes it from two-phase activation consensus.

---

## Troubleshooting

### SDK not activating

**Symptom:** No SDK log messages at startup, `FeatureFlagService` bean not found.

**Cause:** The `feature-flag.server-url` property is missing. The entire auto-configuration is conditional on this property.

**Fix:** Ensure `feature-flag.server-url` is set in `application.yml` or as an environment variable:
```yaml
feature-flag:
  server-url: http://localhost:8080
```

### Flags always returning false

**Symptom:** `FeatureFlagService.isEnabled()` always returns `false`.

**Possible causes:**
1. The server was unreachable at startup, so the initial sync failed. Check logs for connection errors during `SdkLifecycleManager.start()`.
2. The `environment` property does not match any environment configured on the server. Verify `feature-flag.environment` matches a server-side environment.
3. The flag key is misspelled. Flag keys are case-sensitive.

### Strategy proxy not created

**Symptom:** Spring throws `NoUniqueBeanDefinitionException` or you get the wrong implementation injected.

**Possible causes:**
1. The `@FeatureFlagEnabled` and `@FeatureFlagDisabled` annotations reference different flag keys. They must match.
2. One of the implementations is missing a `@Component` annotation.
3. Both implementations do not implement a common interface.

### @FeatureToggle fallback not called

**Symptom:** The annotated method always executes, even when the flag is disabled.

**Possible causes:**
1. The method is being called internally within the same class (self-invocation bypasses Spring AOP proxies). Call the method through the injected bean reference.
2. The fallback method name is misspelled in the annotation.
3. The fallback method signature (parameters and return type) does not match the annotated method.

### @FeatureFlagConfiguration not activating

**Symptom:** Beans inside a `@FeatureFlagConfiguration` class are never created.

**Cause:** This annotation is evaluated at bean definition time from the Spring `Environment`, not from the server. You must set the flag as a static property:
```yaml
feature-flag:
  flags:
    your-flag-key:
      enabled: true
```

A server-side flag toggle will NOT affect `@FeatureFlagConfiguration` -- it requires a restart with the updated property.

### Two-phase activation rolling back

**Symptom:** Flag toggles initiated from the server are rolled back. Logs show `FLAG_ROLLBACK` events.

**Possible causes:**
1. One or more instances failed to ACK within the timeout (default 60 seconds). Check if instances are under heavy load or have Kafka consumer lag.
2. An instance was marked unhealthy (heartbeat timeout) but was still counted in the consensus. Check heartbeat logs.
3. Kafka connectivity issues preventing the PREPARE event from reaching all instances.

### Kafka deserialization errors

**Symptom:** Exceptions in the Kafka consumer related to JSON deserialization.

**Fix:** Ensure your Spring Kafka configuration includes the trusted packages:
```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: com.paymentplatform.*
```

The SDK configures its own `ConsumerFactory` with the correct deserializer settings, but verify there are no conflicting global Kafka configurations.

### Request pinning not working in background threads

**Symptom:** Flag values are inconsistent within a batch job or scheduled task.

**Cause:** Request pinning only works within HTTP request threads (servlet filter). Background threads read directly from the live cache.

**Fix:** If you need consistent flag evaluation in a background context, snapshot the values at the start:
```java
boolean useNewGateway = featureFlagService.isEnabled("new-payment-gateway");
// Use useNewGateway consistently throughout your batch processing
```
