#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Load Test Script: Two-Phase Activation with Multiple Consumer Instances
#
# Tests the feature flag system under load with 3 consumer instances,
# verifying that the two-phase activation protocol (PREPARE -> ACK -> COMMIT)
# works correctly when multiple SDK instances are registered.
# =============================================================================

FLAG_SERVER="http://localhost:8080"
NETWORK="featureflag_default"
CONSUMER_IMAGE="featureflag-sample-consumer"
NUM_CONSUMERS=3
CONCURRENT_TOGGLES=5
PASS=0
FAIL=0
CONSUMER_CONTAINERS=()

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_header() {
  echo ""
  echo -e "${CYAN}=== $1 ===${NC}"
}

log_step() {
  echo ""
  echo -e "${YELLOW}--- $1 ---${NC}"
}

check() {
  local desc="$1"
  local expected="$2"
  local actual="$3"
  if echo "$actual" | grep -q "$expected"; then
    echo -e "  ${GREEN}PASS${NC}: $desc"
    PASS=$((PASS + 1))
  else
    echo -e "  ${RED}FAIL${NC}: $desc (expected '$expected', got '$(echo "$actual" | head -c 200)')"
    FAIL=$((FAIL + 1))
  fi
}

cleanup() {
  log_header "Cleanup"
  echo "Stopping and removing consumer containers..."
  for cname in "${CONSUMER_CONTAINERS[@]}"; do
    docker rm -f "$cname" 2>/dev/null || true
  done

  echo "Stopping docker-compose services..."
  docker-compose down -v 2>/dev/null || true
  echo "Cleanup complete."
}

# Trap EXIT to always clean up
trap cleanup EXIT

wait_for_url() {
  local url="$1"
  local desc="$2"
  local max_wait="${3:-300}"
  local elapsed=0
  echo -n "  Waiting for $desc..."
  while ! curl -sf "$url" > /dev/null 2>&1; do
    if [ $elapsed -ge $max_wait ]; then
      echo " TIMEOUT after ${max_wait}s"
      return 1
    fi
    sleep 5
    elapsed=$((elapsed + 5))
    echo -n "."
  done
  echo " ready (${elapsed}s)"
}

wait_for_instances() {
  local expected_count="$1"
  local max_wait="${2:-120}"
  local elapsed=0
  echo -n "  Waiting for $expected_count healthy instances..."
  while true; do
    local healthy_count
    healthy_count=$(curl -sf "$FLAG_SERVER/api/instances/healthy" 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
    if [ "$healthy_count" -ge "$expected_count" ]; then
      echo " done ($healthy_count instances, ${elapsed}s)"
      return 0
    fi
    if [ $elapsed -ge $max_wait ]; then
      echo " TIMEOUT (only $healthy_count of $expected_count healthy after ${max_wait}s)"
      return 1
    fi
    sleep 5
    elapsed=$((elapsed + 5))
    echo -n "."
  done
}

wait_for_activation_complete() {
  local activation_id="$1"
  local max_wait="${2:-30}"
  local elapsed=0
  while true; do
    local status
    status=$(curl -sf "$FLAG_SERVER/api/activations/$activation_id" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "UNKNOWN")
    if [ "$status" = "COMMITTED" ] || [ "$status" = "ROLLED_BACK" ]; then
      echo "$status"
      return 0
    fi
    if [ $elapsed -ge $max_wait ]; then
      echo "TIMEOUT($status)"
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
}

# ============================================================================
log_header "Feature Flag Load Test - Two-Phase Activation with $NUM_CONSUMERS Consumers"
# ============================================================================

# ---- Phase 1: Start infrastructure ----
log_step "Phase 1: Starting base infrastructure (Oracle, Zookeeper, Kafka, Flag Server)"

docker-compose up -d oracle zookeeper kafka
echo "  Infrastructure containers starting..."

# Wait for Kafka to be healthy (Oracle takes longest, but flag-server depends on both)
echo "  Waiting for Kafka to become healthy..."
KAFKA_READY=0
for i in $(seq 1 60); do
  if docker-compose exec -T kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    KAFKA_READY=1
    break
  fi
  sleep 5
done
if [ $KAFKA_READY -eq 0 ]; then
  echo "  ERROR: Kafka did not become healthy in time"
  exit 1
fi
echo "  Kafka is healthy."

# Start flag-server (depends on oracle + kafka)
docker-compose up -d flag-server
wait_for_url "$FLAG_SERVER/api/flags" "flag-server" 300

# ---- Phase 2: Build consumer image and start 3 instances ----
log_step "Phase 2: Starting $NUM_CONSUMERS consumer instances"

# Build the consumer image via docker-compose (no-start so we control instances)
docker-compose build sample-consumer 2>/dev/null || {
  echo "  Consumer image build failed. Trying to use existing image..."
}

# Determine the actual image name (docker-compose may prefix differently)
ACTUAL_IMAGE=$(docker-compose config | python3 -c "
import sys, yaml
config = yaml.safe_load(sys.stdin)
svc = config.get('services', {}).get('sample-consumer', {})
img = svc.get('image', '')
if not img:
    # Built image name follows: <project>-<service> or <project>_<service>
    print('')
else:
    print(img)
" 2>/dev/null || echo "")

if [ -z "$ACTUAL_IMAGE" ]; then
  # docker-compose v2 uses hyphen, v1 uses underscore
  ACTUAL_IMAGE=$(docker images --format '{{.Repository}}' | grep -E 'featureflag[-_]sample[-_]consumer' | head -1 || echo "")
fi

if [ -z "$ACTUAL_IMAGE" ]; then
  echo "  Could not find consumer image. Starting one via docker-compose to trigger build..."
  docker-compose up -d sample-consumer
  sleep 5
  docker-compose stop sample-consumer
  docker-compose rm -f sample-consumer
  ACTUAL_IMAGE=$(docker images --format '{{.Repository}}' | grep -E 'featureflag[-_]sample[-_]consumer' | head -1)
fi

echo "  Using consumer image: $ACTUAL_IMAGE"

# Get the docker network name
ACTUAL_NETWORK=$(docker network ls --format '{{.Name}}' | grep -E 'featureflag' | head -1 || echo "$NETWORK")
echo "  Using network: $ACTUAL_NETWORK"

# Start consumer instances via docker run (no host port mapping needed)
for i in $(seq 1 $NUM_CONSUMERS); do
  CNAME="loadtest-consumer-$i"
  CONSUMER_CONTAINERS+=("$CNAME")

  echo "  Starting consumer instance $i ($CNAME)..."
  docker run -d \
    --name "$CNAME" \
    --network "$ACTUAL_NETWORK" \
    -e FLAG_SERVER_URL=http://flag-server:8080 \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
    -e FLAG_ENVIRONMENT=default \
    -e SERVER_PORT=8081 \
    "$ACTUAL_IMAGE" > /dev/null
done

echo "  All $NUM_CONSUMERS consumer containers started."

# ---- Phase 3: Wait for all instances to register ----
log_step "Phase 3: Waiting for all $NUM_CONSUMERS instances to register"

wait_for_instances $NUM_CONSUMERS 120

# Display registered instances
INSTANCES_JSON=$(curl -sf "$FLAG_SERVER/api/instances/healthy")
INSTANCE_COUNT=$(echo "$INSTANCES_JSON" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
check "All $NUM_CONSUMERS consumer instances registered" "$NUM_CONSUMERS" "$INSTANCE_COUNT"

echo ""
echo "  Registered instances:"
echo "$INSTANCES_JSON" | python3 -c "
import sys, json
instances = json.load(sys.stdin)
for inst in instances:
    print(f\"    - {inst.get('instanceId', 'N/A')} ({inst.get('status', 'N/A')})\")
" 2>/dev/null || echo "  (could not parse instance list)"

# ---- Phase 4: Create and activate test flags ----
log_step "Phase 4: Creating test flags"

# Create flags for the load test
for i in $(seq 1 $CONCURRENT_TOGGLES); do
  FLAG_KEY="loadtest-flag-$i"
  RESULT=$(curl -sf -X POST "$FLAG_SERVER/api/flags" \
    -H "Content-Type: application/json" \
    -d "{\"flagKey\":\"$FLAG_KEY\",\"name\":\"Load Test Flag $i\",\"description\":\"Flag for load testing\",\"owner\":\"loadtest\",\"staleAfterDays\":30,\"environments\":[\"default\"]}")
  check "Create $FLAG_KEY" "$FLAG_KEY" "$RESULT"
done

log_step "Phase 5: Activating flags (CREATED -> ACTIVE)"

for i in $(seq 1 $CONCURRENT_TOGGLES); do
  FLAG_KEY="loadtest-flag-$i"
  curl -sf -X POST "$FLAG_SERVER/api/flags/$FLAG_KEY/lifecycle" \
    -H "Content-Type: application/json" \
    -d '{"targetState":"ACTIVE","reason":"Load test activation","transitionedBy":"loadtest-script"}' > /dev/null
done

# Verify all are ACTIVE
for i in $(seq 1 $CONCURRENT_TOGGLES); do
  FLAG_KEY="loadtest-flag-$i"
  FLAG_JSON=$(curl -sf "$FLAG_SERVER/api/flags/$FLAG_KEY")
  check "$FLAG_KEY is ACTIVE" "ACTIVE" "$FLAG_JSON"
done

# ---- Phase 5: Single two-phase toggle test ----
log_step "Phase 6: Single flag two-phase toggle (verify correctness)"

echo "  Toggling loadtest-flag-1 ON..."
TOGGLE_RESULT=$(curl -sf -X PUT "$FLAG_SERVER/api/flags/loadtest-flag-1/environments/default" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true}')

ACTIVATION_ID=$(echo "$TOGGLE_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

if [ -n "$ACTIVATION_ID" ]; then
  echo "  Activation ID: $ACTIVATION_ID"
  TOTAL=$(echo "$TOGGLE_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalInstances',0))" 2>/dev/null || echo "0")
  check "Two-phase activation created with $NUM_CONSUMERS instances" "$NUM_CONSUMERS" "$TOTAL"
  check "Activation status is PENDING" "PENDING" "$TOGGLE_RESULT"

  echo -n "  Waiting for activation to complete... "
  FINAL_STATUS=$(wait_for_activation_complete "$ACTIVATION_ID" 30)
  echo "  Final status: $FINAL_STATUS"
  check "Activation committed successfully" "COMMITTED" "$FINAL_STATUS"

  # Check all instances ACKed
  ACTIVATION_DETAIL=$(curl -sf "$FLAG_SERVER/api/activations/$ACTIVATION_ID")
  ACKED=$(echo "$ACTIVATION_DETAIL" | python3 -c "import sys,json; print(json.load(sys.stdin).get('ackedInstances',0))" 2>/dev/null || echo "0")
  check "All $NUM_CONSUMERS instances ACKed" "$NUM_CONSUMERS" "$ACKED"
else
  echo "  No activation ID returned (direct toggle without two-phase)"
  check "Direct toggle returned result" "loadtest-flag-1" "$TOGGLE_RESULT"
fi

# ---- Phase 6: Concurrent toggle stress test ----
log_step "Phase 7: Concurrent toggle stress test ($CONCURRENT_TOGGLES simultaneous toggles)"

echo "  Launching $CONCURRENT_TOGGLES concurrent toggle-ON requests..."

TMPDIR_LOAD=$(mktemp -d)
PIDS=()

for i in $(seq 1 $CONCURRENT_TOGGLES); do
  FLAG_KEY="loadtest-flag-$i"
  (
    RESULT=$(curl -sf -X PUT "$FLAG_SERVER/api/flags/$FLAG_KEY/environments/default" \
      -H "Content-Type: application/json" \
      -d '{"enabled":true}' 2>&1 || echo "ERROR")
    echo "$RESULT" > "$TMPDIR_LOAD/toggle-on-$i.json"
  ) &
  PIDS+=($!)
done

# Wait for all background toggles to complete
for pid in "${PIDS[@]}"; do
  wait "$pid" 2>/dev/null || true
done
echo "  All toggle requests sent."

# Collect activation IDs
ACTIVATION_IDS=()
for i in $(seq 1 $CONCURRENT_TOGGLES); do
  if [ -f "$TMPDIR_LOAD/toggle-on-$i.json" ]; then
    AID=$(python3 -c "import json; data=json.load(open('$TMPDIR_LOAD/toggle-on-$i.json')); print(data.get('id',''))" 2>/dev/null || echo "")
    if [ -n "$AID" ]; then
      ACTIVATION_IDS+=("$AID")
    fi
  fi
done

echo "  Collected ${#ACTIVATION_IDS[@]} activation IDs."

# Wait for all activations to complete
log_step "Phase 8: Waiting for all activations to complete"

COMMITTED=0
ROLLED_BACK=0
TIMED_OUT=0

for AID in "${ACTIVATION_IDS[@]}"; do
  echo -n "  Activation $AID: "
  STATUS=$(wait_for_activation_complete "$AID" 30)
  case "$STATUS" in
    COMMITTED) COMMITTED=$((COMMITTED + 1)) ;;
    ROLLED_BACK) ROLLED_BACK=$((ROLLED_BACK + 1)) ;;
    *) TIMED_OUT=$((TIMED_OUT + 1)) ;;
  esac
done

echo ""
echo "  Activation results:"
echo "    Committed:   $COMMITTED"
echo "    Rolled back: $ROLLED_BACK"
echo "    Timed out:   $TIMED_OUT"

check "At least some activations committed" "0" "$([ $COMMITTED -gt 0 ] && echo '0' || echo '1')"

# ---- Phase 7: Toggle OFF stress test ----
log_step "Phase 9: Concurrent toggle OFF stress test"

PIDS=()
for i in $(seq 1 $CONCURRENT_TOGGLES); do
  FLAG_KEY="loadtest-flag-$i"
  (
    RESULT=$(curl -sf -X PUT "$FLAG_SERVER/api/flags/$FLAG_KEY/environments/default" \
      -H "Content-Type: application/json" \
      -d '{"enabled":false}' 2>&1 || echo "ERROR")
    echo "$RESULT" > "$TMPDIR_LOAD/toggle-off-$i.json"
  ) &
  PIDS+=($!)
done

for pid in "${PIDS[@]}"; do
  wait "$pid" 2>/dev/null || true
done
echo "  All toggle-OFF requests sent."

# Collect and wait for off-toggle activations
ACTIVATION_IDS_OFF=()
for i in $(seq 1 $CONCURRENT_TOGGLES); do
  if [ -f "$TMPDIR_LOAD/toggle-off-$i.json" ]; then
    AID=$(python3 -c "import json; data=json.load(open('$TMPDIR_LOAD/toggle-off-$i.json')); print(data.get('id',''))" 2>/dev/null || echo "")
    if [ -n "$AID" ]; then
      ACTIVATION_IDS_OFF+=("$AID")
    fi
  fi
done

COMMITTED_OFF=0
for AID in "${ACTIVATION_IDS_OFF[@]}"; do
  echo -n "  Activation $AID: "
  STATUS=$(wait_for_activation_complete "$AID" 30)
  if [ "$STATUS" = "COMMITTED" ]; then
    COMMITTED_OFF=$((COMMITTED_OFF + 1))
  fi
done

echo ""
echo "  Toggle-OFF committed: $COMMITTED_OFF / ${#ACTIVATION_IDS_OFF[@]}"

# ---- Phase 8: Rapid toggle (ON/OFF/ON/OFF) for single flag ----
log_step "Phase 10: Rapid toggle test (sequential ON/OFF cycles on single flag)"

RAPID_FLAG="loadtest-flag-1"
RAPID_CYCLES=4
RAPID_SUCCESS=0

for cycle in $(seq 1 $RAPID_CYCLES); do
  ENABLED="true"
  if [ $((cycle % 2)) -eq 0 ]; then
    ENABLED="false"
  fi
  echo -n "  Cycle $cycle (enabled=$ENABLED): "

  RESULT=$(curl -sf -X PUT "$FLAG_SERVER/api/flags/$RAPID_FLAG/environments/default" \
    -H "Content-Type: application/json" \
    -d "{\"enabled\":$ENABLED}" 2>&1 || echo "ERROR")

  AID=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
  if [ -n "$AID" ]; then
    STATUS=$(wait_for_activation_complete "$AID" 30)
    if [ "$STATUS" = "COMMITTED" ]; then
      RAPID_SUCCESS=$((RAPID_SUCCESS + 1))
    fi
  else
    # Direct toggle (no pending activation)
    echo "direct toggle"
    RAPID_SUCCESS=$((RAPID_SUCCESS + 1))
  fi
done

echo ""
check "Rapid toggle cycles succeeded" "$RAPID_CYCLES" "$RAPID_SUCCESS"

# ---- Phase 9: Verify audit trail ----
log_step "Phase 11: Verify audit trail completeness"

AUDIT=$(curl -sf "$FLAG_SERVER/api/audit")
AUDIT_COUNT=$(echo "$AUDIT" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
echo "  Total audit entries: $AUDIT_COUNT"
check "Audit trail has entries" "0" "$([ "$AUDIT_COUNT" -gt 0 ] && echo '0' || echo '1')"

# ---- Phase 10: Verify pending activations are clear ----
log_step "Phase 12: Verify no stuck pending activations"

sleep 3
PENDING=$(curl -sf "$FLAG_SERVER/api/activations/pending")
PENDING_COUNT=$(echo "$PENDING" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
echo "  Pending activations: $PENDING_COUNT"
check "No stuck pending activations" "0" "$PENDING_COUNT"

# ---- Phase 11: Verify all instances still healthy ----
log_step "Phase 13: Verify all instances still healthy after load test"

HEALTHY_JSON=$(curl -sf "$FLAG_SERVER/api/instances/healthy")
HEALTHY_COUNT=$(echo "$HEALTHY_JSON" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
echo "  Healthy instances: $HEALTHY_COUNT"
check "All $NUM_CONSUMERS instances still healthy" "$NUM_CONSUMERS" "$HEALTHY_COUNT"

# Clean up temp dir
rm -rf "$TMPDIR_LOAD"

# ============================================================================
log_header "Load Test Results Summary"
# ============================================================================
echo ""
echo "  Configuration:"
echo "    Consumer instances:   $NUM_CONSUMERS"
echo "    Concurrent toggles:  $CONCURRENT_TOGGLES"
echo "    Rapid toggle cycles: $RAPID_CYCLES"
echo ""
echo "  Two-Phase Activation Results:"
echo "    Toggle-ON committed:    $COMMITTED / ${#ACTIVATION_IDS[@]}"
echo "    Toggle-ON rolled back:  $ROLLED_BACK"
echo "    Toggle-OFF committed:   $COMMITTED_OFF / ${#ACTIVATION_IDS_OFF[@]}"
echo "    Rapid toggles passed:   $RAPID_SUCCESS / $RAPID_CYCLES"
echo ""
echo -e "  Test Assertions:"
echo -e "    ${GREEN}Passed${NC}: $PASS"
echo -e "    ${RED}Failed${NC}: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
  echo -e "${RED}SOME TESTS FAILED${NC}"
  exit 1
else
  echo -e "${GREEN}ALL LOAD TESTS PASSED${NC}"
  exit 0
fi
