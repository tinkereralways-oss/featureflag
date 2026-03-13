# Two-Phase Activation Protocol

## Overview

The two-phase activation protocol ensures that flag state changes are applied consistently across all registered SDK instances before taking effect. It follows a PREPARE-ACK-COMMIT pattern inspired by two-phase commit, using Kafka for broadcast and REST for acknowledgments.

## Protocol Flow

```
  Flag Server                    Kafka                    SDK Instance(s)
      |                            |                            |
  1.  | --- toggleFlag() --------->|                            |
      |   (create PendingActivation                             |
      |    status=PREPARING)       |                            |
      |                            |                            |
  2.  | --- FLAG_PREPARE --------->| --- FLAG_PREPARE --------->|
      |                            |                            |
  3.  |                            |     storePending()         |
      |<------- POST /ack --------|<--------- ACK -------------|
      |   (pessimistic lock,       |                            |
      |    increment ackedCount)   |                            |
      |                            |                            |
  4.  | (all ACKs received)        |                            |
      | --- commitActivation() --->|                            |
      |   (apply toggle,           |                            |
      |    status=COMMITTED)       |                            |
      |                            |                            |
  5.  | --- FLAG_COMMIT ---------->| --- FLAG_COMMIT ---------->|
      |                            |     commitPending()        |
      |                            |                            |
```

### Step-by-step

1. **Initiation** -- When `FlagService.toggleFlag()` is called for a flag/environment, it delegates to `TwoPhaseActivationService.initiateActivation()`. A `PendingActivation` entity is created with status `PREPARING`, recording the target flag, environment, desired enabled state, the count of currently healthy instances, and the timeout.

2. **PREPARE broadcast** -- `KafkaEventPublisher.publishPrepare()` sends a `FLAG_PREPARE` event to the `feature-flag-events` Kafka topic, keyed by `flagKey`. All SDK instances receive this event because each instance uses a unique consumer group (`feature-flag-{instanceId}`).

3. **SDK acknowledgment** -- On receiving `FLAG_PREPARE`, the SDK's `FlagEventListener`:
   - Calls `cacheManager.storePending()` to store the pending state change locally.
   - Calls `serverClient.ack(activationId)` which sends a `POST /api/activations/{activationId}/ack` request back to the server.

4. **ACK processing** -- The server's `ActivationController` receives the ACK and delegates to `TwoPhaseActivationService.receiveAck()`. The activation row is locked with `PESSIMISTIC_WRITE`, the ACK is recorded, and the `ackedInstances` counter is incremented. If `ackedInstances == totalInstances`, the activation commits.

5. **COMMIT broadcast** -- On commit, the flag environment row is updated in the database, a `FLAG_COMMIT` event is published via Kafka, and an audit entry is logged. SDK instances receiving `FLAG_COMMIT` call `cacheManager.commitPending()` to apply the change to their local cache.

## Direct Toggle (No Instances)

When `initiateActivation()` finds no healthy instances registered, it bypasses the two-phase protocol entirely:

- The toggle is applied directly to the `FlagEnvironment` database row.
- An audit entry is logged.
- The method returns `null` (no `ActivationResponse`).
- No Kafka events are published.

This allows the system to function normally during initial setup or when no SDK consumers are running.

## Kafka Events

All events are published to the `feature-flag-events` topic (configurable via `feature-flag.kafka.topic`), keyed by `flagKey` for partition affinity.

### FlagEvent Record

| Field          | Type      | Description                                      |
|----------------|-----------|--------------------------------------------------|
| `activationId` | `string`  | UUID of the `PendingActivation`.                 |
| `eventType`    | `enum`    | `FLAG_PREPARE`, `FLAG_COMMIT`, or `FLAG_ROLLBACK`.|
| `flagKey`      | `string`  | The flag being changed.                          |
| `flagId`       | `string`  | UUID of the flag entity.                         |
| `environment`  | `string`  | Target environment.                              |
| `newEnabled`   | `boolean` | The desired enabled state.                       |
| `timestamp`    | `instant` | When the event was created.                      |

### Event Types

**FLAG_PREPARE** -- Broadcast when a two-phase activation starts. SDK instances should prepare for the change (store pending state) and send an ACK.

**FLAG_COMMIT** -- Broadcast when all ACKs are received. SDK instances should apply the pending change to their local flag cache.

**FLAG_ROLLBACK** -- Broadcast when the activation is rolled back (due to timeout or manual rollback). SDK instances should discard the pending state.

## ACK Mechanism

### Pessimistic Locking

The `PendingActivationRepository.findByIdForUpdate()` method uses `@Lock(PESSIMISTIC_WRITE)` on the activation row. This prevents race conditions when multiple SDK instances send ACKs concurrently -- only one transaction can increment `ackedInstances` at a time.

### Duplicate ACK Handling

If an instance sends the same ACK twice:
- The system checks `ackRepository.existsByActivationIdAndInstanceId()`.
- Duplicate ACKs are logged as warnings and silently ignored.
- The activation response is returned without modification.

### Late ACK Handling

If an ACK arrives after the activation has moved out of `PREPARING` status (e.g., already committed or timed out):
- The ACK is logged as a warning and ignored.
- The current activation response is returned.

### Instance Validation

The ACK endpoint verifies that the acknowledging instance exists in the registry via `instanceRegistryRepository.existsById()`. If the instance is unknown, a `404 Not Found` is returned.

## Conflict Prevention

Before creating a new activation, the service checks for existing activations on the same flag+environment:

- If a `PENDING` activation exists, throws `ActivationConflictException` (HTTP 409).
- If a `PREPARING` activation exists, throws `ActivationConflictException` (HTTP 409).

This prevents overlapping activations that could cause inconsistent state.

## Timeout and Rollback

### Timeout Scheduler

The `ActivationTimeoutScheduler` runs every 5 seconds (configurable via `feature-flag.activation-timeout-check-interval-ms`) and checks all activations in `PENDING` or `PREPARING` status.

An activation is timed out if:
```
current_time > createdAt + timeoutSeconds
```

The default timeout is 60 seconds (configurable via `feature-flag.activation-timeout-seconds`).

### Rollback Process

When a timeout is detected:
1. The activation status is set to `TIMED_OUT`.
2. The `completedAt` timestamp is recorded.
3. A `FLAG_ROLLBACK` event is published via Kafka.
4. SDK instances receiving the rollback discard any pending state via `cacheManager.rollbackPending()`.

The flag environment remains **unchanged** -- the toggle is not applied.

### Rollback Statuses

| Status     | Meaning                                          |
|------------|--------------------------------------------------|
| `TIMED_OUT`| Activation expired before all ACKs were received.|

## Activation Statuses

| Status      | Description                                                          |
|-------------|----------------------------------------------------------------------|
| `PENDING`   | Activation created but not yet processing.                           |
| `PREPARING` | PREPARE event sent, waiting for ACKs from instances.                 |
| `COMMITTED` | All ACKs received, toggle applied, COMMIT event sent.               |
| `TIMED_OUT` | Timeout expired before all ACKs, rollback event sent.               |

## Configuration Properties

| Property                                        | Default  | Description                                    |
|-------------------------------------------------|----------|------------------------------------------------|
| `feature-flag.activation-timeout-seconds`       | `60`     | Seconds before an activation times out.        |
| `feature-flag.activation-timeout-check-interval-ms` | `5000` | Milliseconds between timeout check runs.     |
| `feature-flag.kafka.topic`                      | `feature-flag-events` | Kafka topic for flag events.        |
| `feature-flag.kafka.partitions`                 | `3`      | Number of topic partitions.                    |
| `feature-flag.kafka.replication-factor`         | `1`      | Topic replication factor.                      |

## API Endpoints

### Acknowledge an activation

```bash
curl -X POST http://localhost:8080/api/activations/{activationId}/ack \
  -H "Content-Type: application/json" \
  -d '{"instanceId": "instance-uuid"}'
```

### Check activation status

```bash
curl http://localhost:8080/api/activations/{activationId}
```

### List pending activations

```bash
curl http://localhost:8080/api/activations/pending
```

## SDK-Side Behavior

The SDK's `FlagEventListener` uses a per-instance Kafka consumer group (`feature-flag-{instanceId}`) to ensure every instance receives every event (broadcast semantics). The consumer is configured with:

- `AUTO_OFFSET_RESET=latest` -- Only events published after startup are consumed. A full flag sync happens at startup via REST.
- `USE_TYPE_INFO_HEADERS=false` -- Avoids deserialization issues due to different package names between server and SDK `FlagEvent` classes.

### Cache Behavior During Two-Phase Activation

1. **PREPARE received** -- The pending state is stored separately in the cache via `storePending()`. The current flag value is not changed yet.
2. **COMMIT received** -- The pending state is applied to the main flag cache via `commitPending()`. The flag value now reflects the new state.
3. **ROLLBACK received** -- The pending state is discarded via `rollbackPending()`. The flag value remains unchanged.

During the window between PREPARE and COMMIT/ROLLBACK, `FeatureFlagService.isEnabled()` continues to return the old value. The request-pinning filter (`FeatureFlagRequestFilter`) snapshots all flags at request start, so in-flight requests are never affected mid-request.
