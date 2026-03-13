#!/usr/bin/env bash
set -euo pipefail

# E2E Test Script for Feature Flag System
# Prerequisites: docker-compose up (all services healthy)

FLAG_SERVER="http://localhost:8080"
CONSUMER="http://localhost:8081"
PASS=0
FAIL=0

check() {
  local desc="$1"
  local expected="$2"
  local actual="$3"
  if echo "$actual" | grep -q "$expected"; then
    echo "  PASS: $desc"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $desc (expected '$expected', got '$actual')"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Feature Flag E2E Tests ==="
echo ""

# 1. Create flags
echo "--- Step 1: Create feature flags ---"
CREATE_RESULT=$(curl -s -X POST "$FLAG_SERVER/api/flags" \
  -H "Content-Type: application/json" \
  -d '{"flagKey":"new-payment-gateway","name":"New Payment Gateway","description":"Switch to Stripe","owner":"team-payments","staleAfterDays":90,"environments":["default"]}')
check "Create new-payment-gateway flag" "new-payment-gateway" "$CREATE_RESULT"

CREATE_RESULT2=$(curl -s -X POST "$FLAG_SERVER/api/flags" \
  -H "Content-Type: application/json" \
  -d '{"flagKey":"new-fee-calculation","name":"New Fee Calculation","description":"2.5% fee model","owner":"team-payments","staleAfterDays":90,"environments":["default"]}')
check "Create new-fee-calculation flag" "new-fee-calculation" "$CREATE_RESULT2"

# 2. Verify flags in list
echo ""
echo "--- Step 2: Verify flags exist ---"
FLAGS=$(curl -s "$FLAG_SERVER/api/flags")
check "Flag list contains new-payment-gateway" "new-payment-gateway" "$FLAGS"
check "Flag list contains new-fee-calculation" "new-fee-calculation" "$FLAGS"

# 3. Verify audit log
echo ""
echo "--- Step 3: Verify audit log ---"
AUDIT=$(curl -s "$FLAG_SERVER/api/audit")
check "Audit log has CREATED entries" "CREATED" "$AUDIT"

# 4. Verify consumer registered (give it a moment)
echo ""
echo "--- Step 4: Verify consumer instance registration ---"
sleep 3
INSTANCES=$(curl -s "$FLAG_SERVER/api/instances")
check "Consumer instance registered" "sample-consumer" "$INSTANCES"
check "Instance is HEALTHY" "HEALTHY" "$INSTANCES"

# 5. Check consumer status (flags off by default)
echo ""
echo "--- Step 5: Verify consumer uses legacy gateway (flags OFF) ---"
STATUS=$(curl -s "$CONSUMER/api/payments/status")
check "Gateway is legacy (flag off)" "legacy" "$STATUS"

# 6. Calculate fee with legacy algorithm (3%)
echo ""
echo "--- Step 6: Verify legacy fee calculation ---"
FEE=$(curl -s "$CONSUMER/api/payments/fee?amountCents=10000")
check "Legacy fee is 3% of 10000 = 300" "300" "$FEE"

# 7. Process a payment via legacy gateway
echo ""
echo "--- Step 7: Process payment via legacy gateway ---"
PAYMENT=$(curl -s -X POST "$CONSUMER/api/payments/charge?orderId=ORD-001&amountCents=5000")
check "Payment processed via legacy gateway" "legacy" "$PAYMENT"
check "Payment is successful" "true" "$PAYMENT"

# 8. Activate flags (lifecycle: CREATED -> ACTIVE)
echo ""
echo "--- Step 8: Activate flags ---"
curl -s -X POST "$FLAG_SERVER/api/flags/new-payment-gateway/lifecycle" \
  -H "Content-Type: application/json" \
  -d '{"targetState":"ACTIVE","reason":"Ready for testing","transitionedBy":"admin"}' > /dev/null
curl -s -X POST "$FLAG_SERVER/api/flags/new-fee-calculation/lifecycle" \
  -H "Content-Type: application/json" \
  -d '{"targetState":"ACTIVE","reason":"Ready for testing","transitionedBy":"admin"}' > /dev/null

GATEWAY_FLAG=$(curl -s "$FLAG_SERVER/api/flags/new-payment-gateway")
check "Gateway flag is ACTIVE" "ACTIVE" "$GATEWAY_FLAG"

# 9. Toggle flags ON (triggers two-phase activation)
echo ""
echo "--- Step 9: Toggle flags ON ---"
TOGGLE1=$(curl -s -X PUT "$FLAG_SERVER/api/flags/new-payment-gateway/environments/default" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true}')
TOGGLE2=$(curl -s -X PUT "$FLAG_SERVER/api/flags/new-fee-calculation/environments/default" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true}')

# If two-phase activation is in play, wait for ACK/COMMIT cycle
sleep 5

# 10. Verify consumer now uses Stripe gateway
echo ""
echo "--- Step 10: Verify consumer uses Stripe gateway (flags ON) ---"
STATUS2=$(curl -s "$CONSUMER/api/payments/status")
check "Gateway is stripe (flag on)" "stripe" "$STATUS2"
check "new-payment-gateway flag enabled" "true" "$STATUS2"

# 11. Calculate fee with new algorithm (2.5%)
echo ""
echo "--- Step 11: Verify new fee calculation ---"
FEE2=$(curl -s "$CONSUMER/api/payments/fee?amountCents=10000")
check "New fee is 2.5% of 10000 = 250" "250" "$FEE2"

# 12. Process payment via Stripe gateway
echo ""
echo "--- Step 12: Process payment via Stripe gateway ---"
PAYMENT2=$(curl -s -X POST "$CONSUMER/api/payments/charge?orderId=ORD-002&amountCents=5000")
check "Payment processed via stripe gateway" "stripe" "$PAYMENT2"

# 13. Verify lifecycle transition history
echo ""
echo "--- Step 13: Verify lifecycle history ---"
HISTORY=$(curl -s "$FLAG_SERVER/api/flags/new-payment-gateway/lifecycle/history")
check "History shows CREATED->ACTIVE transition" "ACTIVE" "$HISTORY"

# 14. Verify dashboard loads
echo ""
echo "--- Step 14: Verify dashboard pages ---"
DASH=$(curl -s -o /dev/null -w "%{http_code}" "$FLAG_SERVER/dashboard")
check "Dashboard loads (200)" "200" "$DASH"
INST_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FLAG_SERVER/dashboard/instances")
check "Instances page loads (200)" "200" "$INST_PAGE"
AUDIT_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FLAG_SERVER/dashboard/audit")
check "Audit page loads (200)" "200" "$AUDIT_PAGE"

# 15. Dashboard CRUD: Create flag via UI form
echo ""
echo "--- Step 15: Create flag via dashboard form ---"
DASH_CREATE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$FLAG_SERVER/dashboard/flags" \
  -d "flagKey=dashboard-test-flag&name=Dashboard+Test+Flag&description=Created+via+E2E&owner=e2e-test&staleAfterDays=30&environments=default,staging")
check "Dashboard create redirects (302)" "302" "$DASH_CREATE"

DASH_FLAG=$(curl -s "$FLAG_SERVER/api/flags/dashboard-test-flag")
check "Flag created via dashboard exists" "dashboard-test-flag" "$DASH_FLAG"
check "Flag has correct name" "Dashboard Test Flag" "$DASH_FLAG"
check "Flag has correct owner" "e2e-test" "$DASH_FLAG"

# 16. Dashboard CRUD: Edit flag via UI form
echo ""
echo "--- Step 16: Edit flag via dashboard form ---"
DASH_EDIT=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$FLAG_SERVER/dashboard/flags/dashboard-test-flag/edit" \
  -d "name=Updated+Dashboard+Flag&description=Edited+via+E2E&owner=e2e-updated&staleAfterDays=60")
check "Dashboard edit redirects (302)" "302" "$DASH_EDIT"

EDITED_FLAG=$(curl -s "$FLAG_SERVER/api/flags/dashboard-test-flag")
check "Flag name updated" "Updated Dashboard Flag" "$EDITED_FLAG"
check "Flag owner updated" "e2e-updated" "$EDITED_FLAG"

# 17. Dashboard CRUD: Lifecycle transition via dashboard
echo ""
echo "--- Step 17: Lifecycle transition via dashboard ---"
DASH_LIFECYCLE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$FLAG_SERVER/dashboard/flags/dashboard-test-flag/lifecycle" \
  -d "targetState=ACTIVE")
check "Dashboard lifecycle redirects (302)" "302" "$DASH_LIFECYCLE"

ACTIVE_FLAG=$(curl -s "$FLAG_SERVER/api/flags/dashboard-test-flag")
check "Flag transitioned to ACTIVE" "ACTIVE" "$ACTIVE_FLAG"

# 18. Dashboard CRUD: Add environment via dashboard
echo ""
echo "--- Step 18: Add environment via dashboard ---"
DASH_ADD_ENV=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$FLAG_SERVER/dashboard/flags/dashboard-test-flag/environments" \
  -d "environment=production")
check "Dashboard add-env redirects (302)" "302" "$DASH_ADD_ENV"

ENV_FLAG=$(curl -s "$FLAG_SERVER/api/flags/dashboard-test-flag")
check "Production environment added" "production" "$ENV_FLAG"

# 19. Dashboard CRUD: Toggle environment via dashboard
echo ""
echo "--- Step 19: Toggle environment via dashboard ---"
DASH_TOGGLE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$FLAG_SERVER/dashboard/flags/dashboard-test-flag/environments/default/toggle" \
  -d "enabled=true")
check "Dashboard toggle redirects (302)" "302" "$DASH_TOGGLE"
sleep 3
TOGGLED_FLAG=$(curl -s "$FLAG_SERVER/api/flags/dashboard-test-flag")
check "Default environment toggled ON" '"enabled":true' "$TOGGLED_FLAG"

# 20. Dashboard CRUD: Archive (delete) via dashboard
echo ""
echo "--- Step 20: Archive flag via dashboard ---"
DASH_DELETE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$FLAG_SERVER/dashboard/flags/dashboard-test-flag/delete")
check "Dashboard archive redirects (302)" "302" "$DASH_DELETE"

ARCHIVED_FLAG=$(curl -s "$FLAG_SERVER/api/flags/dashboard-test-flag")
check "Flag is ARCHIVED" "ARCHIVED" "$ARCHIVED_FLAG"

# 21. Dashboard CRUD: Verify all new pages load
echo ""
echo "--- Step 21: Verify dashboard CRUD pages load ---"
CREATE_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FLAG_SERVER/dashboard/flags/new")
check "Create flag page loads (200)" "200" "$CREATE_PAGE"
EDIT_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FLAG_SERVER/dashboard/flags/new-payment-gateway/edit")
check "Edit flag page loads (200)" "200" "$EDIT_PAGE"
DETAIL_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FLAG_SERVER/dashboard/flags/new-payment-gateway")
check "Detail page loads (200)" "200" "$DETAIL_PAGE"

# 22. Dashboard CRUD: Verify audit trail captures dashboard actions
echo ""
echo "--- Step 22: Verify audit trail for dashboard actions ---"
AUDIT_ALL=$(curl -s "$FLAG_SERVER/api/audit")
check "Audit contains dashboard-test-flag entries" "dashboard-test-flag" "$AUDIT_ALL"

# Summary
echo ""
echo "=== Results ==="
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
  echo "SOME TESTS FAILED"
  exit 1
else
  echo "ALL TESTS PASSED"
  exit 0
fi
