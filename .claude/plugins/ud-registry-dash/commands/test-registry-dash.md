---
description: Run the Registry Dashboard UI test plan after checking for drift since the last reviewed commit.
---

Invoke the `test-registry-dash` skill to execute the Registry Dashboard UI test plan.

The skill will:
1. Compare HEAD against the last-reviewed commit recorded in `.claude/plugins/ud-registry-dash/skills/test-registry-dash/metadata.json` for files matching the watch list in `paths.txt`.
2. If drift is detected, gate execution behind a forced test-plan update (worktree → PR), or require the user to type `proceed-without-test-updates` verbatim to bypass.
3. Prompt for the test environment: local-dev | alpha | sandbox. Production is rejected.
4. Drive Chrome MCP to execute the 19-test plan in `test-plan.md`.
5. Report pass/fail per test.

Args: `$ARGUMENTS` (optional — pass `--force-update` to skip straight to test-plan update flow).
