---
name: test-registry-dash
description: Use when the user invokes /test-registry-dash, asks to run the Registry Dashboard UI test plan, or wants to verify dashboard functionality end-to-end. Detects drift in dashboard source since the last reviewed commit and gates test execution on the test plan being up-to-date.
---

# Test Registry Dashboard

Drives the canonical UI test plan for the Nomulus Registry Dashboard against a chosen environment using Chrome MCP automation.

## Files in this skill

- `test-plan.md` — the canonical test plan. **Source of truth for what gets tested.**
- `metadata.json` — `{ lastReviewedCommit, lastReviewedAt, lastReviewer }`. Updated only when a test-plan update is merged to master.
- `paths.txt` — newline-separated git pathspecs to watch for drift.
- `helpers/check-drift.sh` — prints commits and changed files since `lastReviewedCommit` for the watched paths.
- `helpers/start-local-stack.sh` — idempotently starts test server + angular dev for `local-dev` runs. Fetches `ANTHROPIC_API_KEY` from Secret Manager.
- `helpers/seed-test-data.sh` — seeds the testcontainers Postgres with extra rows (e.g. pricing rules) so dashboard pages have non-trivial data. Optional — most tests pass against the default Fixture.java data.

## Workflow

### Phase 0 — Environment bring-up (local-dev only)

If the user picks `local-dev` in Phase 2, the skill is responsible for the local stack — do **not** ask the user to run servers manually. Run `bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/start-local-stack.sh`. The script:

- Reuses anything already listening on `:8080` / `:4200`.
- Starts the Java test server (`./gradlew :core:runTestServer`) if needed; waits up to 180s for cold start.
- Starts the Angular dev server (`npm start` from `console-webapp/`) if needed; waits up to 60s.
- Pulls `ANTHROPIC_API_KEY` from Secret Manager (`AI_TRAFFIC_ANALYZER_ANTHROPIC_API_KEY` in `unstoppable-domains` GCP project) so the AI sparkle endpoints actually call Claude.
- Pass `--restart` to kill stale gradle daemons / ng serve processes first; useful when a previous session left zombies.

If Docker isn't running, the script exits with a clear message — surface it to the user; we cannot start testcontainers without Docker.

### Phase 1 — Drift detection (always first)

1. Read `metadata.json` and `paths.txt` from this skill's directory.
2. Run `bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/check-drift.sh`.
3. If output is empty: no drift, proceed to Phase 2.
4. If drift is detected: print the drift summary (commits + changed files) and **stop** — go to Phase 1b.

### Phase 1b — Drift handling

Show the user the drift summary, then offer three options:

**a) Update the test plan now.** Ask the user where to make the update:
- **In the current branch** — bundle the test-plan update into the active feature branch. Use this when the drifting commits are part of the work the user is currently developing/reviewing; the test-plan changes ride along in the same PR.
- **In a separate branch / worktree** — create a new branch (or use the `superpowers:using-git-worktrees` skill) off `master`, commit the test-plan update there, and open a standalone `chore(registry-dash):` PR against `master`. Use this when the drifting commits already merged and the user just needs to bump the metadata.
- **Defer — handle separately later** — record the drift in the report and proceed to Phase 2 with a warning. Use this when the user wants to deal with the test-plan update on their own time.

In either of the first two paths, read the diff for watched paths, propose updates to `test-plan.md`, propose updates to `metadata.json` (advance `lastReviewedCommit` to current HEAD, set `lastReviewer` to the git user). Commit; if a standalone PR, open it against `master` with title prefix `chore(registry-dash):`. Then continue to Phase 2 (or stop, depending on how disruptive the test-plan changes were — ask the user).

**b) Skip the update — proceed anyway.** Confirm with the user (a normal yes/confirm is enough; no verbatim phrase). On confirmation: log a warning into the test report ("⚠️ Tests run against drifted code; results may not reflect the current code state."), and continue to Phase 2.

**c) Cancel.** Stop and exit cleanly.

### Phase 2 — Environment selection

Ask the user to pick a test environment:

- **local-dev** — `http://localhost:4200/console`. The skill will bring up the stack itself in Phase 0 — the user does **not** need to start anything manually.
- **alpha** — `https://console.dnex-alpha.com/console`
- **sandbox** — `https://console.dnex-sandbox.com/console` (or whatever the actual URL is — verify before navigating)
- **production** — **REJECT.** Production is never a valid test environment.

Verify the chosen environment's URL with the user before proceeding.

### Phase 2b — Test data (local-dev only, optional)

The default test-server `Fixture.java` data covers tests 1-6, 15: 2 TLDs (`example`, `xn--q9jyb4c`), `TheRegistrar` + `NewRegistrar` (with domains), and OTE registrars with no domains. That's enough to verify sparkle visibility, prompt menus, the streaming AI flow, and the prompts endpoint.

For tests that need richer data — Test 14 (Pricing analysis) needs at least one `RegistrarPricing` row, Test 7-8 are more meaningful with multi-TLD revenue history — run `bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/seed-test-data.sh` after the test server is up. The script is idempotent. If a richer dataset is available locally (e.g. `local-test-data-setup.sql` from a prior session), prefer that.

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

## Streaming endpoints

The AI sparkle flow uses Server-Sent Events on `/console-api/registry-dash/ai/analyze`. The Angular client owns the response body and reads it with a single `ReadableStream` reader. Do NOT install fetch/XHR interceptors that call `response.clone()` (or otherwise tee the underlying stream) on this endpoint during a test run — `clone()` produces two parallel readers racing the same stream, and the client-side reader will throw mid-stream. The modal's catch path then renders "Response interrupted. Try again?" even though the server completed the SSE response cleanly.

Safe ways to inspect a streaming response during a sweep:

- Pass-through capture: if you must observe the bytes from JS, return a new `Response` whose body is a single `ReadableStream` you control, read each chunk once, forward it to the original consumer, and accumulate a copy for inspection. (`Response.body` is read-only on an existing response, so the interceptor must construct a replacement, not mutate in place.) One reader, no `clone()`, no `tee()`.
- Out-of-band observation (preferred): Chrome DevTools (Network tab → EventStream / Response), the browser network log via Chrome MCP, or the test server logs. None of these touch the in-page response object, so they cannot perturb the stream.

If the modal shows "Response interrupted. Try again?" during a `/test-registry-dash` run, assume tooling first, not the app. Before logging it as a bug:

1. Check the Network tab / Chrome MCP network log — did `/console-api/registry-dash/ai/analyze` return 200 with a complete event stream?
2. Check the test server logs — did the handler finish without error?
3. Confirm no interceptor in the current session is calling `response.clone()` / `body.tee()` on `/console-api/registry-dash/ai/analyze`.

Only if all three confirm a real failure is this an app bug worth filing.

## Constraints

- Do not modify `metadata.json` outside of an open PR. Local edits to it are not authoritative.
- Do not run tests against production under any circumstance.
- Browser automation requires Chrome MCP tools (`mcp__claude-in-chrome__*`); load them via ToolSearch before driving the browser.

## When NOT to use

- If the user asks a one-off "is the dashboard working?" question — just open the browser and look. This skill is for the full canonical sweep.
- If the dashboard isn't relevant — this skill only covers `/registry-dash/*` pages.
