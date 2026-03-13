# Flag Lifecycle States and Transitions

## Overview

Every feature flag follows a lifecycle state machine that governs when it can be toggled, when it should be cleaned up, and when it is permanently archived. The lifecycle is independent of the flag's enabled/disabled state in any given environment.

## States

| State      | Description                                                                                         |
|------------|-----------------------------------------------------------------------------------------------------|
| `CREATED`  | Initial state. The flag has been defined but is not yet in active use.                              |
| `ACTIVE`   | The flag is in use and may be toggled on/off in any environment.                                    |
| `RETIRED`  | The flag has been marked for removal. It still exists but should no longer be relied upon.          |
| `ARCHIVED` | Terminal state. The flag is permanently archived and cannot transition to any other state.           |

## State Diagram

```
              +----------+
              | CREATED  |
              +----+-----+
                   |
          +--------+--------+
          |                 |
          v                 |
     +--------+             |
     | ACTIVE |             |
     +---+----+             |
         |                  |
    +----+-----+            |
    |          |            |
    v          |            v
+---------+   |      +----------+
| RETIRED |   +----->| ARCHIVED |
+----+----+          +----------+
     |                    ^
     +--------------------+
```

## Valid Transitions

| From State | Allowed Target States     |
|------------|---------------------------|
| `CREATED`  | `ACTIVE`, `ARCHIVED`      |
| `ACTIVE`   | `RETIRED`, `ARCHIVED`     |
| `RETIRED`  | `ARCHIVED`, `ACTIVE`      |
| `ARCHIVED` | (none -- terminal state)  |

### Transition Details

**CREATED -> ACTIVE**
The flag is activated for use. This is the standard path when a flag is ready for rollout.

**CREATED -> ARCHIVED**
The flag was defined but never used. Skips directly to archive for cleanup.

**ACTIVE -> RETIRED**
The flag has served its purpose or is stale. It is marked for removal. This transition can happen manually or automatically via staleness detection.

**ACTIVE -> ARCHIVED**
Direct archive from active use, bypassing the retired state. Useful for emergency cleanup.

**RETIRED -> ACTIVE**
Re-activates a previously retired flag. Allows a flag to be brought back into service if retirement was premature.

**RETIRED -> ARCHIVED**
Completes the retirement process by permanently archiving the flag.

**ARCHIVED -> (any)**
Not allowed. `ARCHIVED` is a terminal state. Attempting to transition out of it returns a `400 Bad Request` error.

## Guard Conditions

The `FlagLifecycleService.validateTransition()` method enforces two rules:

1. **No self-transitions** -- Attempting to transition a flag to its current state returns an error: `"Flag is already in state {state}"`.

2. **Allowed target check** -- The target state must be in the set of valid transitions for the current state. If not, the error message includes the list of valid targets: `"Cannot transition from {from} to {to}. Valid transitions from {from}: {validTargets}"`.

Both violations throw `InvalidLifecycleTransitionException`, which maps to HTTP `400 Bad Request`.

## Staleness Detection

The `StalenessCheckScheduler` runs on a cron schedule (default: daily at 2:00 AM, configurable via `feature-flag.staleness-check-cron`).

### How Staleness Is Determined

A flag is considered stale if:
1. The flag is in `ACTIVE` state.
2. The flag has a `staleAfterDays` value set (and greater than 0).
3. The flag's `updatedAt` timestamp is older than `staleAfterDays` days ago.

If `staleAfterDays` is null or zero, the flag is never considered stale.

The default staleness threshold can be set at creation time via the `staleAfterDays` field on `CreateFlagRequest`. The server-wide default is 90 days (`feature-flag.default-stale-after-days`).

### Staleness Behavior

When a stale flag is detected, one of two things happens depending on configuration:

**When `feature-flag.auto-retire-stale-flags` is `true`:**
The scheduler automatically transitions the flag from `ACTIVE` to `RETIRED` with the reason: `"Auto-retired: flag stale for more than {N} days"`. The transition is attributed to `system`.

**When `feature-flag.auto-retire-stale-flags` is `false` (default):**
The scheduler logs a `STALE_WARNING` audit entry for the flag. The warning is only logged once per flag (checked via `auditLogRepository.existsByFlagIdAndAction`), preventing repeated warnings on subsequent runs.

### Configuration Properties

| Property                                | Default           | Description                                        |
|-----------------------------------------|-------------------|----------------------------------------------------|
| `feature-flag.staleness-check-cron`     | `0 0 2 * * *`    | Cron expression for the staleness check schedule.  |
| `feature-flag.default-stale-after-days` | `90`              | Default days before a flag is considered stale.    |
| `feature-flag.auto-retire-stale-flags`  | `false`           | Whether to auto-retire stale flags or just warn.   |

## Audit Trail

Every lifecycle transition is recorded in two places:

1. **Flag Transition table** -- Stores `fromState`, `toState`, `reason`, `transitionedBy`, and `createdAt`. Queried via `GET /api/flags/{flagKey}/lifecycle/history`.

2. **Audit Log** -- A `LIFECYCLE_TRANSITION` action is logged with the old state as `oldValue` and the new state as `newValue`.

## API Usage

### Transition a flag

```bash
curl -X POST http://localhost:8080/api/flags/new-checkout-flow/lifecycle \
  -H "Content-Type: application/json" \
  -d '{
    "targetState": "ACTIVE",
    "reason": "Feature ready for production rollout",
    "transitionedBy": "jane@example.com"
  }'
```

### View transition history

```bash
curl http://localhost:8080/api/flags/new-checkout-flow/lifecycle/history
```
