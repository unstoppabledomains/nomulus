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
# Print commits + changed files since metadata.lastReviewedCommit for the paths in paths.txt.
# Empty output = no drift. Non-empty output = drift detected.

set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
META="$SKILL_DIR/metadata.json"
PATHS_FILE="$SKILL_DIR/paths.txt"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if [[ ! -f "$META" ]]; then
  echo "ERROR: metadata.json not found at $META" >&2
  exit 2
fi
if [[ ! -f "$PATHS_FILE" ]]; then
  echo "ERROR: paths.txt not found at $PATHS_FILE" >&2
  exit 2
fi

LAST_COMMIT="$(grep -oE '"lastReviewedCommit"[^,}]*' "$META" | sed -E 's/.*"lastReviewedCommit"[^"]*"([^"]+)".*/\1/')"

if [[ -z "${LAST_COMMIT:-}" ]]; then
  echo "ERROR: lastReviewedCommit missing in metadata.json" >&2
  exit 2
fi

cd "$REPO_ROOT"

if ! git rev-parse --verify --quiet "$LAST_COMMIT" >/dev/null; then
  echo "ERROR: lastReviewedCommit $LAST_COMMIT not found in repo (fetch upstream?)" >&2
  exit 2
fi

# Build pathspec args from paths.txt (skip blank lines and comments).
PATHSPECS=()
while IFS= read -r line; do
  [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
  PATHSPECS+=("$line")
done < "$PATHS_FILE"

if [[ ${#PATHSPECS[@]} -eq 0 ]]; then
  echo "ERROR: no pathspecs in paths.txt" >&2
  exit 2
fi

COMMITS="$(git log --oneline "$LAST_COMMIT"..HEAD -- "${PATHSPECS[@]}" 2>/dev/null || true)"
FILES="$(git diff --name-only "$LAST_COMMIT"..HEAD -- "${PATHSPECS[@]}" 2>/dev/null || true)"

if [[ -z "$COMMITS" && -z "$FILES" ]]; then
  exit 0
fi

echo "=== DRIFT DETECTED ==="
echo "Last reviewed commit: $LAST_COMMIT"
echo "Current HEAD: $(git rev-parse --short HEAD)"
echo
if [[ -n "$COMMITS" ]]; then
  echo "Commits since last review (touching watched paths):"
  echo "$COMMITS"
  echo
fi
if [[ -n "$FILES" ]]; then
  echo "Changed files since last review:"
  echo "$FILES"
fi
