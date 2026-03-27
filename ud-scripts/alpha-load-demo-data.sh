#!/usr/bin/env bash
# Copyright 2026 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# ==========================================================================
# Load Registry Dashboard Demo Data into Alpha-GKE via Console API
# ==========================================================================
# Uses the console API (not direct SQL) with IAP authentication.
# Requires credentials from: cd console-webapp && npm run auth:alpha
#
# Usage:
#   ./alpha-load-demo-data.sh              # Load demo data
#   ./alpha-load-demo-data.sh --reset      # Wipe dashboard data first, then reload
#   ./alpha-load-demo-data.sh --dry-run    # Show what would be done, don't execute
# ==========================================================================

set -euo pipefail

CREDS_FILE="$HOME/.config/nomulus-local-dev/credentials.json"
CONSOLE_URL="https://console.dnex-alpha.com"

# --- Parse args ---
RESET=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --reset)   RESET=true ;;
    --dry-run) DRY_RUN=true ;;
    *)         echo "Unknown arg: $arg"; exit 1 ;;
  esac
done

# --- Load credentials ---
if [ ! -f "$CREDS_FILE" ]; then
  echo "ERROR: No credentials at $CREDS_FILE"
  echo "  Run: cd console-webapp && npm run auth:alpha"
  exit 1
fi

CLIENT_ID=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['client_id'])")
CLIENT_SECRET=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['client_secret'])")
REFRESH_TOKEN=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['refresh_token'])")
IAP_CLIENT_ID=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['iap_client_id'])")

# --- Get IAP ID token ---
echo "Authenticating with IAP..."
TOKEN_RESPONSE=$(curl -s -X POST "https://oauth2.googleapis.com/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}&refresh_token=${REFRESH_TOKEN}&audience=${IAP_CLIENT_ID}&grant_type=refresh_token")

ID_TOKEN=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id_token',''))" 2>/dev/null)
if [ -z "$ID_TOKEN" ]; then
  echo "ERROR: Failed to get ID token. Response:"
  echo "$TOKEN_RESPONSE"
  echo ""
  echo "Try re-authenticating: cd console-webapp && npm run auth:alpha"
  exit 1
fi

# Decode and show who we're authenticated as
EMAIL=$(echo "$ID_TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('email','unknown'))" 2>/dev/null || echo "unknown")
echo "  Authenticated as: $EMAIL"

# --- Get XSRF token (required for POST/PUT requests) ---
echo "Fetching XSRF token..."
XSRF_TOKEN=$(curl -s -D - "${CONSOLE_URL}/console-api/userdata" \
  -H "Authorization: Bearer ${ID_TOKEN}" -o /dev/null 2>&1 \
  | grep -i 'set-cookie.*X-CSRF' | sed 's/.*X-CSRF-Token=//;s/;.*//')
if [ -z "$XSRF_TOKEN" ]; then
  echo "ERROR: Failed to get XSRF token from /console-api/userdata"
  exit 1
fi
echo "  XSRF token acquired."

# --- Helper: API call ---
api_post() {
  local path="$1"
  local payload="$2"
  local label="$3"

  if [ "$DRY_RUN" = true ]; then
    echo "  [dry-run] POST $path: $payload"
    return 0
  fi

  local status
  local body
  body=$(curl -s -w "\n%{http_code}" -X POST "${CONSOLE_URL}${path}" \
    -H "Authorization: Bearer ${ID_TOKEN}" \
    -H "Content-Type: application/json" \
    -b "X-CSRF-Token=${XSRF_TOKEN}" \
    -d "$payload")

  status=$(echo "$body" | tail -1)
  body=$(echo "$body" | sed '$d')

  if [ "$status" -ge 200 ] && [ "$status" -lt 300 ]; then
    echo "  OK: $label"
    return 0
  elif [ "$status" -eq 409 ] || [ "$status" -eq 400 ] || [ "$status" -eq 500 ]; then
    # 409/400 = already exists; 500 = backend NPE on duplicate (known bug)
    echo "  SKIP (likely exists): $label"
    return 0
  else
    echo "  FAIL ($status): $label"
    echo "    Response: $body"
    return 1
  fi
}

api_get() {
  local path="$1"
  curl -s -X GET "${CONSOLE_URL}${path}" \
    -H "Authorization: Bearer ${ID_TOKEN}" \
    -H "Content-Type: application/json"
}

# ==========================================================================
# Step 0: Reset (if requested)
# ==========================================================================
if [ "$RESET" = true ]; then
  echo ""
  echo "=== Resetting existing dashboard data ==="
  echo "  Fetching current admin data..."

  ADMIN_DATA=$(api_get "/console-api/registry-dash/admin")

  # Delete registries (cascades to TLDs + users)
  REGISTRY_IDS=$(echo "$ADMIN_DATA" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for r in data.get('registries', []):
    print(r['id'])
" 2>/dev/null || true)

  for rid in $REGISTRY_IDS; do
    api_post "/console-api/registry-dash/admin" \
      "{\"action\":\"deleteRegistry\",\"registryId\":$rid}" \
      "Delete registry ID $rid"
  done

  # Delete pricing rules (need SQL for bulk delete — API has no bulk endpoint)
  echo "  NOTE: Per-registrar pricing and cost basis entries require SQL to bulk-delete."
  echo "  If those tables need clearing, run: alpha-reset-demo-data.sql"
fi

# ==========================================================================
# Step 1: Create Registries
# ==========================================================================
echo ""
echo "=== Creating Registries ==="

api_post "/console-api/registry-dash/admin" \
  '{"action":"createRegistry","registryName":"RetroTech Registry"}' \
  "Registry: RetroTech Registry"

api_post "/console-api/registry-dash/admin" \
  '{"action":"createRegistry","registryName":"Vintage Digital"}' \
  "Registry: Vintage Digital"

api_post "/console-api/registry-dash/admin" \
  '{"action":"createRegistry","registryName":"NeonWave Networks"}' \
  "Registry: NeonWave Networks"

# ==========================================================================
# Step 2: Get registry IDs (need them for TLD + user mapping)
# ==========================================================================
echo ""
echo "=== Fetching registry IDs ==="

ADMIN_DATA=$(api_get "/console-api/registry-dash/admin")

get_registry_id() {
  local name="$1"
  echo "$ADMIN_DATA" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for r in data.get('registries', []):
    if r['name'] == '$name':
        print(r['id'])
        break
" 2>/dev/null
}

RETROTECH_ID=$(get_registry_id "RetroTech Registry")
VINTAGE_ID=$(get_registry_id "Vintage Digital")
NEONWAVE_ID=$(get_registry_id "NeonWave Networks")

echo "  RetroTech Registry: ID $RETROTECH_ID"
echo "  Vintage Digital:    ID $VINTAGE_ID"
echo "  NeonWave Networks:  ID $NEONWAVE_ID"

if [ -z "$RETROTECH_ID" ] || [ -z "$VINTAGE_ID" ] || [ -z "$NEONWAVE_ID" ]; then
  if [ "$DRY_RUN" = true ]; then
    echo "  (Registries don't exist yet — using placeholder IDs for dry-run)"
    RETROTECH_ID=1
    VINTAGE_ID=2
    NEONWAVE_ID=3
  else
    echo "ERROR: Failed to get registry IDs. Admin response:"
    echo "$ADMIN_DATA" | python3 -m json.tool 2>/dev/null || echo "$ADMIN_DATA"
    exit 1
  fi
fi

# ==========================================================================
# Step 3: Map TLDs to Registries
# ==========================================================================
echo ""
echo "=== Mapping TLDs to Registries ==="

# RetroTech Registry: modem, floppy
api_post "/console-api/registry-dash/admin" \
  "{\"action\":\"addTld\",\"registryId\":$RETROTECH_ID,\"tld\":\"modem\"}" \
  "RetroTech <- modem"
api_post "/console-api/registry-dash/admin" \
  "{\"action\":\"addTld\",\"registryId\":$RETROTECH_ID,\"tld\":\"floppy\"}" \
  "RetroTech <- floppy"

# Vintage Digital: pixel, dialup
api_post "/console-api/registry-dash/admin" \
  "{\"action\":\"addTld\",\"registryId\":$VINTAGE_ID,\"tld\":\"pixel\"}" \
  "Vintage Digital <- pixel"
api_post "/console-api/registry-dash/admin" \
  "{\"action\":\"addTld\",\"registryId\":$VINTAGE_ID,\"tld\":\"dialup\"}" \
  "Vintage Digital <- dialup"

# NeonWave Networks: cassette
api_post "/console-api/registry-dash/admin" \
  "{\"action\":\"addTld\",\"registryId\":$NEONWAVE_ID,\"tld\":\"cassette\"}" \
  "NeonWave <- cassette"

# ==========================================================================
# Step 4: Map Users to Registries
# ==========================================================================
echo ""
echo "=== Mapping Users to Registries ==="

# Map current user to all registries
for RID in $RETROTECH_ID $VINTAGE_ID $NEONWAVE_ID; do
  api_post "/console-api/registry-dash/admin" \
    "{\"action\":\"addUser\",\"registryId\":$RID,\"userEmail\":\"torrey@unstoppabledomains.com\"}" \
    "User torrey@ -> registry $RID"
done

# ==========================================================================
# Step 5: Cost Basis — global fee schedules (registrarId = null)
# ==========================================================================
echo ""
echo "=== Creating Cost Basis (global fee schedules) ==="

# TLD pricing reference (retail → wholesale cost basis is ~55-65%):
#   modem:    Create $15, Renew $12, Restore $25
#   floppy:   Create $10, Renew $8,  Restore $18
#   pixel:    Create $20, Renew $16, Restore $35
#   dialup:   Create $12, Renew $10, Restore $20
#   cassette: Create $18, Renew $14, Restore $30

cost_basis() {
  local tld="$1" op="$2" amount="$3"
  api_post "/console-api/registry-dash/cost-basis" \
    "{\"tld\":\"$tld\",\"operation\":\"$op\",\"costAmount\":$amount,\"costCurrency\":\"USD\",\"effectiveDate\":\"2026-01-01T00:00:00Z\",\"notes\":\"Wholesale $op — $tld\"}" \
    "Cost basis: $tld/$op = \$$amount"
}

cost_basis modem    CREATE   9.00
cost_basis modem    RENEW    7.50
cost_basis modem    RESTORE 15.00
cost_basis modem    TRANSFER 7.50

cost_basis floppy   CREATE   6.00
cost_basis floppy   RENEW    5.00
cost_basis floppy   RESTORE 10.00
cost_basis floppy   TRANSFER 5.00

cost_basis pixel    CREATE  12.00
cost_basis pixel    RENEW   10.00
cost_basis pixel    RESTORE 20.00
cost_basis pixel    TRANSFER 10.00

cost_basis dialup   CREATE   7.00
cost_basis dialup   RENEW    6.00
cost_basis dialup   RESTORE 12.00
cost_basis dialup   TRANSFER 6.00

cost_basis cassette CREATE  11.00
cost_basis cassette RENEW    9.00
cost_basis cassette RESTORE 18.00
cost_basis cassette TRANSFER 9.00

# ==========================================================================
# Step 6: Per-Registrar Pricing Overrides
# ==========================================================================
echo ""
echo "=== Creating Per-Registrar Pricing Rules ==="

# Helper to create pricing rule
pricing() {
  local reg="$1" tld="$2" op="$3" amount="$4"
  api_post "/console-api/registry-dash/pricing" \
    "{\"registrarId\":\"$reg\",\"tld\":\"$tld\",\"operation\":\"$op\",\"priceAmount\":$amount,\"priceCurrency\":\"USD\",\"effectiveDate\":\"2026-01-01T00:00:00Z\",\"isActive\":true}" \
    "Pricing: $reg/$tld/$op = \$$amount"
}

# arsenic: broad portfolio (5 TLDs), mix of discounts and premiums
pricing arsenic modem    CREATE  14.00
pricing arsenic modem    RENEW   11.50
pricing arsenic modem    RESTORE 28.00
pricing arsenic floppy   CREATE   9.00
pricing arsenic floppy   RENEW    7.50
pricing arsenic pixel    CREATE  22.00
pricing arsenic pixel    RENEW   15.00
pricing arsenic dialup   CREATE  11.00
pricing arsenic cassette CREATE  19.00
pricing arsenic cassette RENEW   13.00

# mercury: competitive discounts
pricing mercury modem  CREATE  13.50
pricing mercury modem  RENEW   11.00
pricing mercury floppy CREATE   8.50
pricing mercury floppy RENEW    7.00
pricing mercury dialup CREATE  10.00
pricing mercury dialup RENEW    9.00

# thallium: premium pricing
pricing thallium modem  CREATE  16.50
pricing thallium modem  RENEW   13.00
pricing thallium pixel  CREATE  23.00
pricing thallium pixel  RENEW   18.00
pricing thallium dialup CREATE  14.00

# radium: at-cost or slight discounts
pricing radium modem    CREATE  15.00
pricing radium modem    RENEW   12.00
pricing radium floppy   CREATE  10.00
pricing radium cassette CREATE  17.00
pricing radium cassette RENEW   13.50

# polonium: small portfolio
pricing polonium modem CREATE  14.50
pricing polonium modem RENEW   11.75
pricing polonium pixel CREATE  19.00
pricing polonium pixel RENEW   15.50

# cadmium: single TLD specialist (deep discounts)
pricing cadmium floppy CREATE   8.00
pricing cadmium floppy RENEW    6.50
pricing cadmium floppy RESTORE 15.00

# antimony: single TLD, slight premium
pricing antimony modem CREATE  15.50
pricing antimony modem RENEW   12.50

# ==========================================================================
# Step 7: Verification
# ==========================================================================
echo ""
echo "=== Verification ==="

FINAL_ADMIN=$(api_get "/console-api/registry-dash/admin")
echo "$FINAL_ADMIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for r in data.get('registries', []):
    tlds = ', '.join(t['tld'] for t in r.get('tlds', []))
    users = ', '.join(u['userEmail'] for u in r.get('users', []))
    print(f\"  {r['name']}: TLDs=[{tlds}] Users=[{users}]\")
print()
si = data.get('systemInfo', {})
print(f\"  System TLDs: {', '.join(si.get('tlds', []))}\")
print(f\"  System Registrars: {len(si.get('registrars', []))}\")
" 2>/dev/null

echo ""
echo "Done! Dashboard data loaded via API."
echo ""
echo "To view: https://console.dnex-alpha.com/console#/registry-dash/overview"
