---
name: test-registry-dash
description: Use when the user invokes /test-registry-dash, asks to run the Registry Dashboard UI test plan, or wants to verify dashboard functionality end-to-end. Detects drift in dashboard source since the last reviewed commit and gates test execution on the test plan being up-to-date.
---

# Test Registry Dashboard

Drives the canonical UI test plan for the Nomulus Registry Dashboard against a chosen environment using Chrome MCP automation.

## Files in this skill

- `test-plan.md` — the canonical 19-test plan. **Source of truth for what gets tested.**
- `metadata.json` — `{ lastReviewedCommit, lastReviewedAt, lastReviewer }`. Updated only when a test-plan update is merged to master.
- `paths.txt` — newline-separated git pathspecs to watch for drift.
- `helpers/check-drift.sh` — prints commits and changed files since `lastReviewedCommit` for the watched paths.

## Workflow

### Phase 1 — Drift detection (always first)

1. Read `metadata.json` and `paths.txt` from this skill's directory.
2. Run `bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/check-drift.sh`.
3. If output is empty: no drift, proceed to Phase 2.
4. If drift is detected: print the drift summary (commits + changed files) and **stop** — go to Phase 1b.

### Phase 1b — Drift handling

Show the user the drift summary, then offer three options:

**a) Recommended: Update the test plan in a new worktree, PR to master.**
- Ask the user: "What branch should the test-plan update branch off?"
  - Default suggestion: `master` (clean baseline, use when not actively developing)
  - Alternative: current branch (use when feature work is what's driving the test update)
- Use the `superpowers:using-git-worktrees` skill to create the worktree.
- Inside the worktree: read the diff for watched paths, propose updates to `test-plan.md`, propose updates to `metadata.json` (advance `lastReviewedCommit` to current HEAD, set `lastReviewer` to the git user).
- Commit and open a PR against `master`. Title prefix `chore(registry-dash):`.
- After PR is opened: stop. Tell the user "merge the PR, then re-run /test-registry-dash on a clean branch."

**b) Skip the update — proceed anyway.**
- Require the user to type the **exact** phrase `proceed-without-test-updates` in their next message. Anything else (even close paraphrases) means abort.
- If they type it correctly: log a warning into the test report ("⚠️ Tests run against drifted code; results may not reflect the current code state."), and continue to Phase 2.
- This path exists for emergencies. Discourage it.

**c) Cancel.**
- Stop and exit cleanly.

### Phase 2 — Environment selection

Ask the user to pick a test environment:

- **local-dev** — `http://localhost:4200/console` (requires local Angular dev server + test server with `ANTHROPIC_API_KEY`)
- **alpha** — `https://console.dnex-alpha.com/console`
- **sandbox** — `https://console.dnex-sandbox.com/console` (or whatever the actual URL is — verify before navigating)
- **production** — **REJECT.** Production is never a valid test environment.

Verify the chosen environment's URL with the user before proceeding.

### Phase 3 — Test execution

1. Read `test-plan.md`.
2. Use `TaskCreate` to create one task per numbered test in the plan.
3. Open Chrome MCP, navigate to the chosen environment, log in if needed (the user's session should already be valid).
4. Execute each test in sequence. For each:
   - Mark `in_progress` via `TaskUpdate`.
   - Drive the browser per the test's steps.
   - Capture pass/fail evidence (screenshots, JS query results, network responses).
   - Mark `completed` (or `in_progress` with notes if blocked).

### Phase 4 — Reporting

Print a summary table with one row per test: ✅ Passed / ⚠️ Partial / ❌ Failed / ⏭️ Skipped. For any non-pass, include a one-line root cause and a file path or URL pointer.

If any new bugs are discovered, offer to log them as Linear tickets in the RSP project, Registry Dashboard milestone, with `bug` label.

## Constraints

- Do not modify `metadata.json` outside of an open PR. Local edits to it are not authoritative.
- Do not run tests against production under any circumstance.
- The override phrase `proceed-without-test-updates` must match **verbatim**. Do not accept paraphrases or partial matches.
- Browser automation requires Chrome MCP tools (`mcp__claude-in-chrome__*`); load them via ToolSearch before driving the browser.

## When NOT to use

- If the user asks a one-off "is the dashboard working?" question — just open the browser and look. This skill is for the full canonical sweep.
- If the dashboard isn't relevant — this skill only covers `/registry-dash/*` pages.
