# Plan: `run_explore_query` AI tool

**Date:** 2026-04-30
**Spec:** `docs/superpowers/specs/2026-04-30-registry-dash-ai-tier3-explore-tool.md`

## Step-by-step (TDD-flavored)

1. [x] Add `RegistryConfigSettings.Ai.Tools` nested class with `maxRows`, `statementTimeoutSeconds`.
2. [x] Add `@Provides @Config("aiToolsMaxRows")` and `@Config("aiToolsStatementTimeoutSeconds")` providers in `RegistryConfig` (defaults 500 and 30).
3. [x] Add `ai.tools` block to `core/.../config/files/default-config.yaml` (default values + comments).
4. [x] Update `ai.prompts.toolsHeader` in `default-config.yaml` to add the generic-vs-specific tie-breaker.
5. [x] Bump `ai.prompts.version` from `v1.0.1` to `v1.0.2`.
6. [x] Extend `ToolJpaHelper.runExplore` with an overload that takes `int statementTimeoutSeconds`. Original signature delegates with 0 (no timeout) for backward compat. Convert `IllegalArgumentException` from `validate()` to `AiToolException`. Detect Postgres SQLSTATE 57014 and convert to `AiToolException("Query exceeded Ns — try a narrower date range or smaller scope.")`.
7. [x] Implement `RunExploreQueryTool` (`name`, `description`, `inputSchema`, `execute`). `description()` enumerates all 7 data sources × allowed metrics/dimensions, derived from `ExploreDataSource` getters. Audit log via FluentLogger.
8. [x] Add `RunExploreQueryTool` constructor param to `AiToolRegistry`.
9. [x] Add `run_explore_query: '🔬 Running data query'` to `console-webapp/.../ai-analysis.models.ts` `TOOL_LABELS`.
10. [x] Create `AiToolTestBase` with shared JPA extension, fake clock, user fixtures, and `assertAiToolException`.
11. [x] Write `RunExploreQueryToolTest` — schema validation, missing args, unknown data_source, invalid metric/dimension per source, permission denial, description smoke.
12. [x] Backfill tests for `QueryTransfersTool`, `GetPricingRulesTool`, `QueryRegistrarActivityTool`, `QueryDomainDetailsTool`.
13. [x] Update `.claude/plugins/ud-registry-dash/skills/test-registry-dash/test-plan.md` — add E2E step for `run_explore_query` (positive + negative).
14. [ ] Run `./gradlew :core:compileJava :core:compileTestJava` — must be clean.
15. [ ] Run `./gradlew :core:test --tests "google.registry.ai.tools.*"` — all green.
16. [ ] Frontend build: `cd console-webapp && nvm use 22.16.0 && npm run build`.
17. [ ] Local E2E with `ud-registry-dash:test-registry-dash` skill — happy path + negative path.
18. [ ] Open PR against `unstoppabledomains/nomulus` master.
19. [ ] After merge: open follow-up PR in `unstoppabledomains/nomulus-secrets` mirroring the `ai.tools` block and `ai.prompts.toolsHeader`.
20. [ ] Write follow-up prompt at `bozeman-v1/.context/tier3-followup-extended-tool-audit-logging-prompt.md` (Option B audit-log refactor for after both this and the sibling specific-tools PR merge).

## Notes / decisions

- Schema option A locked (constrained per-data-source wrapper).
- Statement-timeout adopted in this PR (scoped to `RunExploreQueryTool` via the new
  `runExplore` overload). Broadening to the existing 4 tools is the deferred follow-up.
- Audit log uses a separate FluentLogger in the tool. Consolidation into the orchestrator's
  `toolsUsed` line is deferred so it can update both this PR's tools and the sibling
  specific-tools PR's tools at once.
- Backfilled tests focus on input validation + permission denial (the safety net). Happy-path
  tests with real fixtures are exercised via the local-stack E2E.
