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
# Idempotently start the local Nomulus stack for the registry-dashboard test
# plan: test server (Java + testcontainers Postgres) on :8080, Angular dev
# server on :4200. Fetches ANTHROPIC_API_KEY from Secret Manager so the AI
# sparkle endpoints work.
#
# Usage:
#   bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/start-local-stack.sh [--restart]
#
# --restart   Kill any existing test-server / angular-dev / gradle daemons
#             before starting fresh. Default behavior reuses anything already
#             listening on the expected ports.
#
# Output:
#   Prints status lines as each layer comes up. Exits 0 once both ports
#   respond, non-zero on any failure.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../../.." && pwd)"
LOG_DIR="${REPO_ROOT}/.context/test-registry-dash"
mkdir -p "$LOG_DIR"
TEST_SERVER_LOG="$LOG_DIR/test-server.log"
ANGULAR_LOG="$LOG_DIR/angular-dev.log"

RESTART=0
for arg in "$@"; do
  if [[ "$arg" == "--restart" ]]; then
    RESTART=1
  fi
done

port_is_open() {
  curl -sf --max-time 2 -o /dev/null "http://localhost:$1/" \
    || curl -sf --max-time 2 -o /dev/null "http://[::1]:$1/"
}

kill_stack() {
  echo "[start-local-stack] killing existing test server, angular dev, gradle daemons…"
  pkill -9 -f "RegistryTestServer" 2>/dev/null || true
  pkill -9 -f "runTestServer" 2>/dev/null || true
  pkill -9 -f "GradleWorker" 2>/dev/null || true
  pkill -9 -f "gradle.*Daemon" 2>/dev/null || true
  pkill -9 -f "ng serve" 2>/dev/null || true
  pkill -9 -f "@angular/cli/bin/ng" 2>/dev/null || true
  sleep 2
}

if [[ "$RESTART" == "1" ]]; then
  kill_stack
fi

# 1. Docker daemon — required for testcontainers Postgres.
if ! docker info >/dev/null 2>&1; then
  echo "[start-local-stack] ERROR: Docker daemon not running. Start Docker Desktop and re-run." >&2
  exit 1
fi

# 2. ANTHROPIC_API_KEY — fetch from Secret Manager if not set.
if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "[start-local-stack] fetching ANTHROPIC_API_KEY from Secret Manager…"
  ANTHROPIC_API_KEY="$(gcloud secrets versions access latest \
    --secret=AI_TRAFFIC_ANALYZER_ANTHROPIC_API_KEY \
    --project=unstoppable-domains 2>/dev/null || true)"
  if [[ -z "$ANTHROPIC_API_KEY" ]]; then
    echo "[start-local-stack] WARN: could not fetch ANTHROPIC_API_KEY. AI analyze calls will fail." >&2
  else
    export ANTHROPIC_API_KEY
  fi
fi

# 3. Test server.
if port_is_open 8080; then
  echo "[start-local-stack] test server already up on :8080 — reusing."
else
  echo "[start-local-stack] starting test server (logs: $TEST_SERVER_LOG)…"
  (
    cd "$REPO_ROOT"
    nohup ./gradlew :core:runTestServer > "$TEST_SERVER_LOG" 2>&1 &
  )
  echo "[start-local-stack] waiting for :8080 (cold start can take 2-3 min)…"
  for i in $(seq 1 180); do
    if port_is_open 8080; then break; fi
    sleep 1
  done
  if ! port_is_open 8080; then
    echo "[start-local-stack] ERROR: test server did not come up on :8080 within 180s. See $TEST_SERVER_LOG" >&2
    exit 2
  fi
  echo "[start-local-stack] test server up."
fi

# 4. Angular dev server.
if port_is_open 4200; then
  echo "[start-local-stack] angular dev server already up on :4200 — reusing."
else
  echo "[start-local-stack] starting angular dev server (logs: $ANGULAR_LOG)…"
  if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
    # shellcheck disable=SC1091
    . "$HOME/.nvm/nvm.sh"
    nvm use 22.16.0 >/dev/null 2>&1 || nvm use --lts >/dev/null 2>&1 || true
  fi
  (
    cd "$REPO_ROOT/console-webapp"
    nohup npm start > "$ANGULAR_LOG" 2>&1 &
  )
  echo "[start-local-stack] waiting for :4200…"
  for i in $(seq 1 60); do
    if port_is_open 4200; then break; fi
    sleep 1
  done
  if ! port_is_open 4200; then
    echo "[start-local-stack] ERROR: angular dev server did not come up on :4200 within 60s. See $ANGULAR_LOG" >&2
    exit 3
  fi
  echo "[start-local-stack] angular dev server up."
fi

echo "[start-local-stack] ready: open http://localhost:4200/console"
