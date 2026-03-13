# REST API Reference

Base URL: `http://localhost:8080`

---

## Flags

### Create a Flag

**POST** `/api/flags`

Creates a new feature flag with optional environment configurations.

**Request Body:**

| Field           | Type       | Required | Validation                                                       |
|-----------------|------------|----------|------------------------------------------------------------------|
| `flagKey`       | `string`   | Yes      | Must not be blank. Only lowercase letters, numbers, and hyphens. |
| `name`          | `string`   | Yes      | Must not be blank.                                               |
| `description`   | `string`   | No       |                                                                  |
| `owner`         | `string`   | No       |                                                                  |
| `staleAfterDays`| `integer`  | No       | Number of days before the flag is considered stale.              |
| `environments`  | `string[]` | No       | List of environment names (e.g. `["default", "production"]`).    |

**Example Request:**

```json
{
  "flagKey": "new-checkout-flow",
  "name": "New Checkout Flow",
  "description": "Enables the redesigned checkout experience",
  "owner": "payments-team",
  "staleAfterDays": 90,
  "environments": ["default", "production"]
}
```

**Response:** `201 Created`

```json
{
  "id": "a1b2c3d4-...",
  "flagKey": "new-checkout-flow",
  "name": "New Checkout Flow",
  "description": "Enables the redesigned checkout experience",
  "owner": "payments-team",
  "lifecycleState": "CREATED",
  "staleAfterDays": 90,
  "environments": [
    { "id": 1, "environment": "default", "enabled": false, "rolloutPercentage": null },
    { "id": 2, "environment": "production", "enabled": false, "rolloutPercentage": null }
  ],
  "createdAt": "2026-03-12T10:00:00Z",
  "updatedAt": "2026-03-12T10:00:00Z"
}
```

**Error Responses:**
- `400 Bad Request` -- Validation failure (blank fields, invalid `flagKey` pattern).
- `409 Conflict` -- A flag with the same `flagKey` already exists.

---

### List All Flags

**GET** `/api/flags`

Returns all feature flags.

**Response:** `200 OK`

```json
[
  {
    "id": "...",
    "flagKey": "new-checkout-flow",
    "name": "New Checkout Flow",
    "description": "...",
    "owner": "payments-team",
    "lifecycleState": "ACTIVE",
    "staleAfterDays": 90,
    "environments": [...],
    "createdAt": "...",
    "updatedAt": "..."
  }
]
```

---

### Get a Flag

**GET** `/api/flags/{flagKey}`

Returns a single flag by its key.

**Path Parameters:**
- `flagKey` -- The unique key of the flag.

**Response:** `200 OK` -- Same structure as the create response.

**Error Responses:**
- `404 Not Found` -- Flag with the given key does not exist.

---

### Update a Flag

**PUT** `/api/flags/{flagKey}`

Updates metadata fields of an existing flag. Only provided fields are updated; omitted fields remain unchanged.

**Path Parameters:**
- `flagKey` -- The unique key of the flag.

**Request Body:**

| Field           | Type      | Required | Description                          |
|-----------------|-----------|----------|--------------------------------------|
| `name`          | `string`  | No       | Display name of the flag.            |
| `description`   | `string`  | No       | Description text.                    |
| `owner`         | `string`  | No       | Team or person owning the flag.      |
| `staleAfterDays`| `integer` | No       | Days before the flag is stale.       |

**Response:** `200 OK` -- Updated `FlagResponse`.

**Error Responses:**
- `404 Not Found` -- Flag not found.

---

### Toggle a Flag in an Environment

**PUT** `/api/flags/{flagKey}/environments/{environment}`

Enables or disables a flag in a specific environment. If healthy SDK instances are registered, this triggers the two-phase activation protocol. Otherwise, the toggle is applied directly.

**Path Parameters:**
- `flagKey` -- The unique key of the flag.
- `environment` -- The target environment (e.g. `default`, `production`).

**Request Body:**

| Field     | Type      | Required | Description                     |
|-----------|-----------|----------|---------------------------------|
| `enabled` | `boolean` | Yes      | The desired enabled state.      |

**Response:** `200 OK`

When two-phase activation is used, returns an `ActivationResponse`:

```json
{
  "id": "activation-uuid",
  "flagId": "flag-uuid",
  "environment": "default",
  "newEnabled": true,
  "status": "PREPARING",
  "totalInstances": 3,
  "ackedInstances": 0,
  "timeoutSeconds": 60,
  "ackedInstanceIds": [],
  "createdAt": "...",
  "completedAt": null
}
```

When no instances are registered, the toggle applies immediately and the response body may be `null`.

**Error Responses:**
- `404 Not Found` -- Flag not found.
- `409 Conflict` -- An activation is already pending or in progress for this flag/environment combination.

---

### Transition Flag Lifecycle

**POST** `/api/flags/{flagKey}/lifecycle`

Transitions a flag to a new lifecycle state.

**Path Parameters:**
- `flagKey` -- The unique key of the flag.

**Request Body:**

| Field           | Type     | Required | Description                                       |
|-----------------|----------|----------|---------------------------------------------------|
| `targetState`   | `string` | Yes      | Target state: `ACTIVE`, `RETIRED`, or `ARCHIVED`. |
| `reason`        | `string` | No       | Reason for the transition.                        |
| `transitionedBy`| `string` | Yes      | Who initiated the transition.                     |

**Response:** `200 OK`

```json
{
  "flagId": "flag-uuid",
  "flagKey": "new-checkout-flow",
  "fromState": "CREATED",
  "toState": "ACTIVE",
  "reason": "Feature is ready for rollout",
  "transitionedBy": "jane@example.com",
  "transitionedAt": "2026-03-12T10:30:00Z"
}
```

**Error Responses:**
- `400 Bad Request` -- Invalid target state or transition not allowed from the current state.
- `404 Not Found` -- Flag not found.

---

### Get Lifecycle Transition History

**GET** `/api/flags/{flagKey}/lifecycle/history`

Returns the full transition history for a flag, ordered most recent first.

**Path Parameters:**
- `flagKey` -- The unique key of the flag.

**Response:** `200 OK`

```json
[
  {
    "flagId": "...",
    "flagKey": "new-checkout-flow",
    "fromState": "CREATED",
    "toState": "ACTIVE",
    "reason": "Ready for rollout",
    "transitionedBy": "jane@example.com",
    "transitionedAt": "..."
  }
]
```

**Error Responses:**
- `404 Not Found` -- Flag not found.

---

## Instances

### Register an Instance

**POST** `/api/instances/register`

Registers an SDK instance with the server. Typically called automatically by the SDK at startup.

**Request Body:**

| Field         | Type      | Required | Description                        |
|---------------|-----------|----------|------------------------------------|
| `instanceId`  | `string`  | Yes      | Unique identifier for the instance.|
| `serviceName` | `string`  | Yes      | Name of the consuming service.     |
| `hostAddress` | `string`  | No       | Host address of the instance.      |
| `port`        | `integer` | No       | Port number.                       |
| `sdkVersion`  | `string`  | No       | SDK version string.                |

**Response:** `201 Created`

```json
{
  "instanceId": "instance-uuid",
  "serviceName": "payment-service",
  "hostAddress": "10.0.1.5",
  "port": 8081,
  "healthStatus": "HEALTHY",
  "sdkVersion": "1.0.0",
  "lastHeartbeat": "...",
  "registeredAt": "..."
}
```

**Error Responses:**
- `400 Bad Request` -- Validation failure.

---

### Send Heartbeat

**POST** `/api/instances/heartbeat`

Updates the last heartbeat timestamp for a registered instance. The SDK sends this every 15 seconds.

**Request Body:**

| Field        | Type     | Required | Description                        |
|--------------|----------|----------|------------------------------------|
| `instanceId` | `string` | Yes      | The instance sending the heartbeat.|

**Response:** `200 OK` -- `InstanceStatusResponse`.

**Error Responses:**
- `404 Not Found` -- Instance not registered.

---

### Deregister an Instance

**DELETE** `/api/instances/{instanceId}`

Removes an instance from the registry. Called by the SDK during graceful shutdown.

**Path Parameters:**
- `instanceId` -- The unique identifier of the instance.

**Response:** `204 No Content`

---

### List All Instances

**GET** `/api/instances`

Returns all registered instances regardless of health status.

**Response:** `200 OK` -- Array of `InstanceStatusResponse`.

---

### List Healthy Instances

**GET** `/api/instances/healthy`

Returns only instances with `HEALTHY` status.

**Response:** `200 OK` -- Array of `InstanceStatusResponse`.

---

### Get Instance Details

**GET** `/api/instances/{instanceId}`

Returns details for a specific instance.

**Path Parameters:**
- `instanceId` -- The unique identifier of the instance.

**Response:** `200 OK` -- `InstanceStatusResponse`.

**Error Responses:**
- `404 Not Found` -- Instance not found.

---

## Activations

### Acknowledge an Activation

**POST** `/api/activations/{activationId}/ack`

SDK instances call this to acknowledge a PREPARE event during two-phase activation.

**Path Parameters:**
- `activationId` -- The activation being acknowledged.

**Request Body:**

| Field        | Type     | Required | Description                           |
|--------------|----------|----------|---------------------------------------|
| `instanceId` | `string` | Yes      | The instance sending the acknowledgment. |

**Response:** `200 OK` -- `ActivationResponse` with updated ACK count.

When all instances have ACKed, the activation automatically commits (status becomes `COMMITTED`).

**Error Responses:**
- `404 Not Found` -- Activation or instance not found.
- `409 Conflict` -- Not applicable here, but duplicate ACKs are silently ignored.

---

### Get Activation Details

**GET** `/api/activations/{activationId}`

Returns the current state of an activation.

**Path Parameters:**
- `activationId` -- The activation ID.

**Response:** `200 OK` -- `ActivationResponse`.

**Error Responses:**
- `404 Not Found` -- Activation not found.

---

### List Pending Activations

**GET** `/api/activations/pending`

Returns all activations in `PENDING` or `PREPARING` status.

**Response:** `200 OK` -- Array of `ActivationResponse`.

---

## Audit

### Get Audit Log (Paginated)

**GET** `/api/audit`

Returns the global audit log with pagination support.

**Query Parameters:**
- `page` -- Page number (0-based, default `0`).
- `size` -- Page size (default `20`).
- `sort` -- Sort field and direction (e.g. `createdAt,desc`).

**Response:** `200 OK` -- Spring `Page` wrapper:

```json
{
  "content": [
    {
      "id": 1,
      "flagId": "flag-uuid",
      "action": "TOGGLED",
      "oldValue": "enabled=false",
      "newValue": "enabled=true",
      "changedBy": "system",
      "metadata": null,
      "createdAt": "..."
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

---

### Get Audit Log for a Flag

**GET** `/api/audit/flag/{flagId}`

Returns all audit entries for a specific flag.

**Path Parameters:**
- `flagId` -- The UUID of the flag (not the flag key).

**Response:** `200 OK` -- Array of `AuditLogResponse`.

---

## Error Response Format

All error responses follow this structure:

```json
{
  "status": 404,
  "message": "Flag not found: my-flag",
  "timestamp": "2026-03-12T10:00:00Z"
}
```

### HTTP Status Code Summary

| Status | Meaning                | Typical Cause                                              |
|--------|------------------------|------------------------------------------------------------|
| 400    | Bad Request            | Validation failure or invalid lifecycle transition.        |
| 404    | Not Found              | Flag, instance, or activation does not exist.              |
| 409    | Conflict               | Duplicate flag key or concurrent activation conflict.      |
| 500    | Internal Server Error  | Unexpected server error (logged server-side).              |
