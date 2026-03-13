# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

All 9 implementation sprints are complete. The system has passed full E2E smoke testing (Docker Compose) and all unit tests (79/79).

**Implementation plan**: `~/.claude/plans/adaptive-sauteeing-treasure.md`
**User guide**: `how-to-use.md` (1,200+ lines — quick start, curl examples, SDK integration, configuration reference)

## Documentation

- `how-to-use.md` — Comprehensive hands-on guide (quick start, full API walkthrough, SDK integration, troubleshooting)
- `docs/sdk-integration-guide.md` — SDK setup, all 4 integration patterns, request pinning, troubleshooting
- `docs/rest-api.md` — All REST endpoints with request/response examples
- `docs/flag-lifecycle.md` — State diagram, valid transitions, staleness detection
- `docs/two-phase-activation.md` — Protocol flow, Kafka events, pessimistic locking, timeout/rollback
- `docs/operations-runbook.md` — Dashboard pages, monitoring, tuning, common operational tasks
- `e2e-test.sh` — End-to-end test script for Docker Compose stack
- `load-test.sh` — Load test with 3 consumer instances, concurrent toggles, rapid cycling

## Build & Test Commands

```bash
# Build all modules
mvn compile

# Run all tests (unit + integration; integration needs Docker for Oracle Testcontainers)
mvn test -pl feature-flag-server

# Run SDK tests
mvn test -pl feature-flag-sdk

# Run a single test class
mvn test -pl feature-flag-server -Dtest=FlagServiceTest

# Run a single test method
mvn test -pl feature-flag-server -Dtest="FlagServiceTest#createFlag_success"

# Full verify (compile + test + package)
mvn verify
```

## Architecture

Multi-module Maven project for a feature flag management system protecting a payment processing platform.

**Modules:**
- `feature-flag-server` — Spring Boot 3.2.5 app: REST API, JPA entities, Liquibase migrations, Kafka producer, Thymeleaf dashboard
- `feature-flag-sdk` — Library jar (no Boot plugin): annotations, AOP, Caffeine cache, Kafka consumer, REST client for consumer services
- `feature-flag-sample-consumer` — Demo app using the SDK

**Key design patterns:**
- **Two-phase activation**: Flag toggles go through PREPARE → ACK → COMMIT/ROLLBACK across all registered instances before taking effect
- **Instance self-registration**: SDK instances register via REST, heartbeat every 15s, marked unhealthy after 45s silence
- **Flag lifecycle state machine**: CREATED → ACTIVE → RETIRED → ARCHIVED with automated staleness detection
- **Audit trail**: Every flag change logged with REQUIRES_NEW propagation (persists even if parent tx rolls back)

## Oracle 21c Conventions

These constraints apply throughout the codebase — do not deviate:

- **UUID**: `VARCHAR2(36)` with Java-side `UUID.randomUUID().toString()` in `@PrePersist` — no DB-generated UUIDs
- **Boolean**: `NUMBER(1)` via `@Column(columnDefinition = "NUMBER(1)")` — no native BOOLEAN type
- **Auto-ID**: `SEQUENCE` with `@GeneratedValue(strategy = SEQUENCE)`, `allocationSize = 1`
- **Timestamps**: `TIMESTAMP WITH TIME ZONE` mapped to `Instant` in Java, defaults via `SYSTIMESTAMP`
- **Large text**: `VARCHAR2(4000)` or `@Lob` for CLOB — no TEXT type
- **Migrations**: Liquibase YAML format in `feature-flag-server/src/main/resources/db/changelog/`
- **Testcontainers**: `gvenzl/oracle-xe:21-slim` image

## Java 17 Constraints

- No record patterns, virtual threads, sequenced collections, or pattern matching for switch (JDK 21+)
- Use `RestTemplate` not `RestClient`
- Caffeine 3.1.x (not 3.2+ which requires JDK 21)
- DTOs as `record` types (stable since JDK 16)
- Auto-configuration via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

## Code Conventions

- **Packages (server)**: `com.paymentplatform.flagserver.{entity,entity.enums,repository,service,controller,dto,exception,scheduler,event,config}`
- **Packages (SDK)**: `com.paymentplatform.flagsdk.{annotation,aop,cache,client,condition,config,event,filter,heartbeat,lifecycle,proxy}`
- **Entity fields**: camelCase in Java, snake_case in DB (Hibernate default mapping)
- **Services**: Constructor injection, `@Transactional` at class level, `@Transactional(readOnly=true)` on query methods
- **DTOs**: Java records with Jakarta validation on request types
- **Audit log entity**: Stores `flagId` as plain String (no JPA FK, no DB FK) to keep audit independent — audit records must survive flag deletion
- **Audit detail**: `FlagService.updateFlag` captures per-field old/new diffs (e.g. `name=Old; owner=old-owner`), skips audit when nothing changed
- **Scheduling**: `@EnableScheduling` on main app class; `HeartbeatMonitorScheduler` runs every 15s via `@Scheduled(fixedDelayString=...)`
- **Batch saves**: Prefer `saveAll()` over looping `save()` for bulk updates (e.g. marking stale instances unhealthy)
- **Tests**: Unit tests use Mockito (`@ExtendWith(MockitoExtension.class)`), integration tests use `@SpringBootTest` + Testcontainers + `@DynamicPropertySource`
- Integration tests disable Kafka via `spring.autoconfigure.exclude=KafkaAutoConfiguration`
- **Testcontainers**: Do NOT use `.withDatabaseName()` on `OracleContainer` — XEPDB1 is the default and cannot be overridden
- **Entity `@PrePersist`**: Always set both `createdAt` and `updatedAt` in `@PrePersist` to avoid null `updatedAt` on first read
- **Column length annotations**: Must match Liquibase migration column sizes (e.g. `@Column(length = 50)` for `VARCHAR2(50)`)
- **Exception handler**: `GlobalExceptionHandler` logs unhandled exceptions via `log.error` before returning generic 500 response
- **Kafka events**: `KafkaEventPublisher` publishes `FlagEvent` records (keyed by `flagKey`) to `feature-flag-events` topic; event types: `FLAG_PREPARE`, `FLAG_COMMIT`, `FLAG_ROLLBACK`
- **Two-phase toggle flow**: `FlagService.toggleFlag()` → `TwoPhaseActivationService.initiateActivation()` → Kafka PREPARE → ACKs via `POST /api/activations/{id}/ack` → pessimistic lock on `pending_activations` row → COMMIT/ROLLBACK. Direct toggle when no healthy instances registered.
- **Pessimistic locking**: `PendingActivationRepository.findByIdForUpdate()` uses `@Lock(PESSIMISTIC_WRITE)` to prevent ACK race conditions
- **Activation timeout**: `ActivationTimeoutScheduler` polls every 5s, rolls back activations past `feature-flag.activation-timeout-seconds` (default 60s)
- **Services with `@Value` params**: `TwoPhaseActivationService` takes `defaultTimeoutSeconds` via `@Value` — unit tests must construct manually (not `@InjectMocks`)
- **Dashboard CRUD**: `DashboardController` handles both read-only views (GET) and mutations (POST) for flag management — create, edit, toggle, lifecycle transitions, add environment, archive/delete
- **Dashboard form pattern**: POST endpoints use `@RequestParam` (not `@ModelAttribute` binding); validation is manual; form values preserved on error via explicit `model.addAttribute("formFieldName", value)`
- **Flash messages**: Use `RedirectAttributes.addFlashAttribute("successMessage"|"errorMessage", ...)` → rendered by `fragments/flash.html` using `.alert-success`/`.alert-danger` CSS classes
- **Dashboard delete**: Soft delete only — archives flag via `FlagLifecycleService.transitionFlag()` to ARCHIVED state; no hard delete exists
- **Dashboard Thymeleaf fragments**: Reusable fragments in `templates/flags/detail-actions.html` (environment toggles, add environment, archive button) included via `th:replace`

## SDK Conventions

- **Auto-configuration**: `FeatureFlagAutoConfiguration` conditional on `feature-flag.server-url` property; registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- **SDK RestTemplate**: Dedicated bean with 5s connect / 10s read timeouts — does NOT use `@ConditionalOnMissingBean` to avoid hijacking the app's RestTemplate
- **Kafka consumer**: Unique consumer group per instance (`feature-flag-{instanceId}`) for broadcast semantics; `USE_TYPE_INFO_HEADERS=false` to handle cross-package deserialization; `AUTO_OFFSET_RESET=latest` since full sync happens at startup
- **Cache**: Caffeine-backed `FlagCacheManager` with pending state support for two-phase activation; `getAllFlags()` returns `Map.copyOf()` snapshot for request pinning
- **Lifecycle**: `SdkLifecycleManager` (SmartLifecycle) — startup: register + full sync; shutdown: deregister
- **Heartbeat**: `HeartbeatManager` sends heartbeat every 15s via SpEL: `#{${feature-flag.heartbeat-interval-seconds:15} * 1000}`
- **Request pinning**: `FeatureFlagRequestFilter` snapshots flags into ThreadLocal at request start, cleared in `finally` block; `FeatureFlagService.isEnabled()` checks pinned values first
- **Strategy pattern**: `@FeatureFlagEnabled`/`@FeatureFlagDisabled` annotations on interface impls; `FeatureFlagProxyBeanRegistrar` (BeanFactoryPostProcessor) auto-detects paired annotations and registers primary proxy beans; `FeatureFlagProxyFactory` creates JDK dynamic proxy routing by flag state
- **AOP pattern**: `@FeatureToggle(flag, fallbackMethod)` with `FeatureToggleAspect`; uses `getDeclaredMethod` + `setAccessible(true)` for non-public fallbacks; unwraps `InvocationTargetException`
- **Configuration pattern**: `@FeatureFlagConfiguration` uses `FeatureFlagCondition` (Spring `@Conditional`) evaluated at bean definition time against `feature-flag.flags.{flagKey}.enabled` property
- **Mockito**: SDK tests use `mock-maker-subclass` (see `src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`) for JDK 25 compatibility
