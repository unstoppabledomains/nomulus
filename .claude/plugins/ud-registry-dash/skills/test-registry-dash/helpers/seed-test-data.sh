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
#
# Seed minimum dashboard test data into the running testcontainers Postgres so
# the registry-dash UI test plan exercises non-trivial data. Idempotent (uses
# ON CONFLICT). Required only for tests 7-14 of test-plan.md; tests 1-6 and 15
# pass against the default Fixture.java data without seeding.
#
# Prereqs:
# - Test server is running (testcontainers Postgres listening on a host port).
# - psql is installed.
#
# Usage:
#   bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/seed-test-data.sh

set -euo pipefail

PG_PORT="$(docker ps --format '{{.Image}} {{.Ports}}' \
  | awk '/postgres:17-alpine/ { match($0, /0\.0\.0\.0:[0-9]+->5432/); if (RSTART) { gsub(/0\.0\.0\.0:|->5432/, "", $0); print substr($0, RSTART, RLENGTH-12); exit } }')"

if [[ -z "$PG_PORT" ]]; then
  echo "[seed-test-data] ERROR: testcontainers Postgres not detected (is the test server running?)" >&2
  exit 1
fi

echo "[seed-test-data] seeding into postgres on :$PG_PORT…"

PGPASSWORD=test psql -h localhost -p "$PG_PORT" -U test -d postgres -v ON_ERROR_STOP=1 <<'SQL'
-- One pricing rule per existing TLD/registrar combo so the Pricing page has
-- something to summarize. Adjust amounts/operations as needed.

INSERT INTO "RegistrarPricing"
  (id, registrar_id, tld, operation, price_amount, price_currency, effective_date, expiry_date, is_active, creation_time)
VALUES
  (gen_random_uuid(), 'TheRegistrar', 'example', 'CREATE', 12.50, 'USD', '2026-01-01', NULL, true, now()),
  (gen_random_uuid(), 'TheRegistrar', 'example', 'RENEW',  10.00, 'USD', '2026-01-01', NULL, true, now()),
  (gen_random_uuid(), 'NewRegistrar', 'example', 'CREATE',  9.50, 'USD', '2026-01-01', NULL, true, now()),
  (gen_random_uuid(), 'NewRegistrar', 'tld',     'CREATE',  8.75, 'USD', '2026-01-01', NULL, true, now())
ON CONFLICT DO NOTHING;
SQL

echo "[seed-test-data] done. RegistrarPricing rows:"
PGPASSWORD=test psql -h localhost -p "$PG_PORT" -U test -d postgres -tAc \
  'SELECT count(*) FROM "RegistrarPricing"'
