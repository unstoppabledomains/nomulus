---
description: Run the Registry Dashboard UI test plan after checking for drift since the last reviewed commit.
---

Invoke the `test-registry-dash` skill to execute the Registry Dashboard UI test plan.

The skill will:
1. Compare HEAD against the last-reviewed commit recorded in `.claude/plugins/ud-registry-dash/skills/test-registry-dash/metadata.json` for files matching the watch list in `paths.txt`.
2. If drift is detected, offer the user three paths: (a) update the test plan now — either bundled into the current branch or as a separate branch/worktree, or defer; (b) proceed anyway with a warning logged into the test report; (c) cancel.
3. Prompt for the test environment: local-dev | alpha | sandbox. Production is rejected.
4. Drive Chrome MCP to execute the test plan in `test-plan.md`.
5. Report pass/fail per test.

Args: `$ARGUMENTS` (optional — pass `--force-update` to skip straight to test-plan update flow).
