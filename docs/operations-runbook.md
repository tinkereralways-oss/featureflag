# Operations Runbook

## Starting the System

### Prerequisites

- Docker and Docker Compose installed.
- Ports 1521 (Oracle), 2181 (Zookeeper), 9092 (Kafka), 8080 (flag server), and 8081 (sample consumer) are available.

### Start with Docker Compose

```bash
docker-compose up -d
```

This starts four services in dependency order:

1. **Oracle XE 21** -- Database. Takes up to 2 minutes to initialize on first start. Health check runs every 30 seconds with a 120-second start period.
2. **Zookeeper** -- Kafka dependency.
3. **Kafka** -- Message broker. Health check confirms the broker API is available.
4. **Flag Server** -- Spring Boot application. Waits for Oracle and Kafka to be healthy before starting. Health check probes `GET /api/flags`.
5. **Sample Consumer** -- Demo SDK consumer. Waits for the flag server to be healthy.

### Verify Services Are Running

```bash
# Check all container statuses
docker-compose ps

# Verify the flag server is responding
curl http://localhost:8080/api/flags

# Verify the sample consumer
curl http://localhost:8081
```

### Start Without Docker (Local Development)

If running the server directly:

```bash
# Start Oracle and Kafka via Docker
docker-compose up -d oracle zookeeper kafka

# Wait for Oracle to be healthy, then start the server
mvn spring-boot:run -pl feature-flag-server
```

The server connects to Oracle at `localhost:1521/XEPDB1` and Kafka at `localhost:9092` by default (see `application.yml`).

---

## Dashboard Pages

The Thymeleaf dashboard is available at `http://localhost:8080/dashboard`.

### Flag List -- `/dashboard`

Displays all feature flags with their lifecycle state, environments, and enabled status.

**Actions:**
- **New Flag** button -- opens the create flag form at `/dashboard/flags/new`.

**Filtering:**
- `lifecycle` query parameter -- Filter by lifecycle state (e.g. `/dashboard?lifecycle=ACTIVE`).
- `search` query parameter -- Free-text search across flag key, name, and owner (e.g. `/dashboard?search=checkout`).

Both filters can be combined: `/dashboard?lifecycle=ACTIVE&search=payment`.

### Create Flag -- `/dashboard/flags/new`

Form to create a new feature flag. Fields: flag key (required, lowercase/numbers/hyphens), name (required), description, owner, stale-after-days (default 90), and environments (comma-separated, defaults to "default"). Redirects to the flag detail page on success.

### Edit Flag -- `/dashboard/flags/{flagKey}/edit`

Form to edit an existing flag's name, description, owner, and staleness threshold. Flag key is read-only. Changes are captured in the audit log with field-level diffs.

### Flag Detail -- `/dashboard/flags/{flagKey}`

Shows a single flag's full details including:
- Metadata (key, name, description, owner, staleness threshold).
- **Edit** button to modify flag metadata (hidden for ARCHIVED flags).
- **Lifecycle transition** buttons showing only valid transitions for the current state.
- **Environment toggles** (ACTIVE flags only) -- toggle each environment ON/OFF. Triggers two-phase activation when SDK instances are registered.
- **Add environment** form (ACTIVE or CREATED flags) -- adds a new environment in OFF state.
- **Archive** button (Danger Zone) -- soft-deletes by transitioning to ARCHIVED state with confirmation dialog.
- Lifecycle transition history with timestamps, actors, and reasons.

### Instance List -- `/dashboard/instances`

Displays all registered SDK instances with:
- Instance ID, service name, host address, and port.
- Health status (`HEALTHY` or `UNHEALTHY`).
- Last heartbeat timestamp.
- Registration timestamp.
- Summary counts: healthy instances vs. total instances.

### Pending Activations -- `/dashboard/activations`

Lists all activations currently in `PENDING` or `PREPARING` status. Useful for monitoring in-progress two-phase activations.

### Activation Detail -- `/dashboard/activations/{activationId}`

Shows full details of a specific activation including:
- Target flag and environment.
- Desired state change.
- ACK progress (how many instances have acknowledged out of total).
- List of instance IDs that have sent ACKs.
- Timeout configuration.
- Timestamps for creation and completion.

### Audit Log -- `/dashboard/audit`

Paginated view of all audit entries across all flags. Supports pagination via query parameters:
- `page` -- Page number (0-based, default 0).
- `size` -- Entries per page (default 25).

---

## Monitoring Instances

### Health Status Model

Instances have two health states:

| Status    | Meaning                                                        |
|-----------|----------------------------------------------------------------|
| `HEALTHY` | Instance is sending heartbeats within the expected interval.   |
| `UNHEALTHY` | Instance has missed heartbeats beyond the threshold.         |

### Heartbeat Monitoring

- SDK instances send heartbeats every **15 seconds** (`feature-flag.heartbeat-interval-seconds`).
- The `HeartbeatMonitorScheduler` runs every 15 seconds and marks instances as `UNHEALTHY` if their last heartbeat is older than **45 seconds** (`feature-flag.unhealthy-threshold-seconds`).
- Unhealthy instances are batch-updated using `saveAll()`.

### Checking Instance Health

```bash
# All instances
curl http://localhost:8080/api/instances

# Only healthy instances
curl http://localhost:8080/api/instances/healthy

# Specific instance
curl http://localhost:8080/api/instances/{instanceId}
```

### Common Instance Issues

**Instance shows UNHEALTHY:**
- The consumer application may have crashed or lost network connectivity.
- Check the consumer application logs for errors.
- Verify Kafka connectivity from the consumer host.

**Instance not appearing at all:**
- The SDK may have failed to register at startup. Check consumer startup logs for registration errors.
- Verify `feature-flag.server-url` is configured correctly in the consumer's application properties.

**Stale instances after redeployment:**
- The SDK calls `deregister` on graceful shutdown. If the process was killed abruptly, the old instance remains registered until marked unhealthy by the heartbeat monitor.

---

## Troubleshooting Activations

### Activation Stuck in PREPARING

**Symptoms:** An activation has been in `PREPARING` status for longer than the timeout period.

**Diagnosis:**
1. Check the activation detail: `curl http://localhost:8080/api/activations/{id}`
2. Compare `ackedInstances` vs `totalInstances` to see which instances have not ACKed.
3. Check the list of `ackedInstanceIds` to identify missing instances.
4. Check if the missing instances are healthy: `curl http://localhost:8080/api/instances`

**Resolution:**
- If the missing instances are unhealthy, the `ActivationTimeoutScheduler` will roll back the activation after the timeout expires (default 60 seconds).
- If instances are healthy but not ACKing, check their logs for Kafka consumer issues.

### Activation Timed Out

**Symptoms:** Activation status is `TIMED_OUT`.

**Cause:** Not all healthy instances acknowledged the PREPARE event within the timeout period.

**Impact:** The flag toggle was **not** applied. The flag remains in its previous state.

**Investigation:**
1. Check which instances ACKed and which did not.
2. Review Kafka consumer lag for the `feature-flag-events` topic.
3. Check the SDK instance logs for errors processing the PREPARE event.

### Activation Conflict (409)

**Symptoms:** Toggle request returns HTTP 409.

**Cause:** There is already a `PENDING` or `PREPARING` activation for the same flag and environment.

**Resolution:** Wait for the existing activation to complete (commit or time out), then retry.

```bash
# Check pending activations
curl http://localhost:8080/api/activations/pending
```

---

## Configuration Tuning

### Server Configuration (`application.yml`)

| Property                                        | Default              | Description                                       |
|-------------------------------------------------|----------------------|---------------------------------------------------|
| `feature-flag.activation-timeout-seconds`       | `60`                 | Seconds before a two-phase activation times out.  |
| `feature-flag.activation-timeout-check-interval-ms` | `5000`           | How often the timeout scheduler runs (ms).        |
| `feature-flag.heartbeat-interval-seconds`       | `15`                 | Expected heartbeat interval from SDK instances.   |
| `feature-flag.unhealthy-threshold-seconds`      | `45`                 | Seconds of silence before an instance is unhealthy.|
| `feature-flag.staleness-check-cron`             | `0 0 2 * * *`       | Cron schedule for staleness detection (daily 2 AM).|
| `feature-flag.default-stale-after-days`         | `90`                 | Default days before a flag is considered stale.   |
| `feature-flag.auto-retire-stale-flags`          | `false`              | Auto-retire stale flags vs. log warnings only.    |
| `feature-flag.kafka.topic`                      | `feature-flag-events`| Kafka topic name for flag events.                 |
| `feature-flag.kafka.partitions`                 | `3`                  | Number of Kafka topic partitions.                 |
| `feature-flag.kafka.replication-factor`         | `1`                  | Kafka topic replication factor.                   |
| `server.port`                                   | `8080`               | HTTP port for the flag server.                    |

### Database Configuration

| Property                            | Default                                  | Description               |
|-------------------------------------|------------------------------------------|---------------------------|
| `spring.datasource.url`            | `jdbc:oracle:thin:@localhost:1521/XEPDB1`| Oracle JDBC URL.          |
| `spring.datasource.username`       | `feature_flag`                           | Database user.            |
| `spring.datasource.password`       | `feature_flag`                           | Database password.        |

### Kafka Configuration

| Property                            | Default          | Description                    |
|-------------------------------------|------------------|--------------------------------|
| `spring.kafka.bootstrap-servers`   | `localhost:9092` | Kafka broker addresses.        |

### Docker Compose Environment Overrides

When running via Docker Compose, the following environment variables override the defaults:

| Variable                            | Value                                    |
|-------------------------------------|------------------------------------------|
| `SPRING_DATASOURCE_URL`            | `jdbc:oracle:thin:@oracle:1521/XEPDB1`  |
| `SPRING_DATASOURCE_USERNAME`       | `feature_flag`                           |
| `SPRING_DATASOURCE_PASSWORD`       | `feature_flag`                           |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS`   | `kafka:29092`                            |

The sample consumer uses:

| Variable                  | Value                      |
|---------------------------|----------------------------|
| `FLAG_SERVER_URL`         | `http://flag-server:8080`  |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092`              |
| `FLAG_ENVIRONMENT`        | `default`                  |

### Tuning Recommendations

**High-latency environments:** Increase `activation-timeout-seconds` (e.g. to 120) and `unhealthy-threshold-seconds` (e.g. to 90) if network latency between instances and server is high.

**Large instance fleets:** If you have many SDK instances, consider increasing Kafka partitions to improve event throughput. Ensure the timeout is long enough for all instances to process and ACK.

**Frequent flag changes:** If activations conflict often, reduce `activation-timeout-seconds` so timed-out activations clear faster.

**Staleness management:** Enable `auto-retire-stale-flags` in production to automatically clean up flags that have not been modified in `staleAfterDays` days. Start with warnings (`false`) to identify flags that need attention before enabling auto-retirement.

---

## Common Operational Tasks

### Create and Activate a Flag

```bash
# Create the flag
curl -X POST http://localhost:8080/api/flags \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-payment-flow",
    "name": "New Payment Flow",
    "owner": "payments-team",
    "staleAfterDays": 90,
    "environments": ["default", "production"]
  }'

# Move to ACTIVE lifecycle state
curl -X POST http://localhost:8080/api/flags/new-payment-flow/lifecycle \
  -H "Content-Type: application/json" \
  -d '{
    "targetState": "ACTIVE",
    "reason": "Ready for rollout",
    "transitionedBy": "ops@example.com"
  }'

# Enable in the default environment
curl -X PUT http://localhost:8080/api/flags/new-payment-flow/environments/default \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'
```

### Retire and Archive a Flag

```bash
# Retire
curl -X POST http://localhost:8080/api/flags/new-payment-flow/lifecycle \
  -H "Content-Type: application/json" \
  -d '{
    "targetState": "RETIRED",
    "reason": "Feature fully rolled out, flag no longer needed",
    "transitionedBy": "ops@example.com"
  }'

# Archive
curl -X POST http://localhost:8080/api/flags/new-payment-flow/lifecycle \
  -H "Content-Type: application/json" \
  -d '{
    "targetState": "ARCHIVED",
    "reason": "Cleanup complete",
    "transitionedBy": "ops@example.com"
  }'
```

### Investigate a Flag's History

```bash
# View lifecycle transitions
curl http://localhost:8080/api/flags/new-payment-flow/lifecycle/history

# View audit log for a specific flag (use the flag's UUID, not the key)
curl http://localhost:8080/api/audit/flag/{flagId}
```

### Emergency: Check and Clear Stuck Activations

```bash
# List all pending activations
curl http://localhost:8080/api/activations/pending

# Check a specific activation
curl http://localhost:8080/api/activations/{activationId}
```

If an activation is stuck, wait for the timeout scheduler to roll it back (runs every 5 seconds). The timeout defaults to 60 seconds from creation.

### Database Access

The Oracle database is accessible at `localhost:1521` with service name `XEPDB1`:

```bash
# Connect via SQL*Plus (if available)
sqlplus feature_flag/feature_flag@localhost:1521/XEPDB1

# Or via Docker
docker-compose exec oracle sqlplus feature_flag/feature_flag@XEPDB1
```

### Viewing Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f flag-server
docker-compose logs -f sample-consumer

# Filter for activation events
docker-compose logs flag-server | grep -i activation
```

### Restarting Services

```bash
# Restart just the flag server
docker-compose restart flag-server

# Restart with a fresh build
docker-compose up -d --build flag-server

# Full restart
docker-compose down && docker-compose up -d
```

**Note:** Restarting the flag server does not lose data -- Oracle persists to a Docker volume (`oracle-data`). SDK instances will re-register automatically on reconnection via the `SdkLifecycleManager`.
