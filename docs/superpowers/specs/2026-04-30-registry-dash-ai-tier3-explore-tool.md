# Tier 3 follow-up: Generic `run_explore_query` AI tool

**Date:** 2026-04-30
**Author:** torrey@unstoppabledomains.com
**Parent spec:** `2026-04-29-registry-dash-ai-tier3-design.md`
**Status:** Implementing

## Context

Tier 3 v1 shipped four specific AI tools — `query_transfers`, `get_pricing_rules`,
`query_registrar_activity`, `query_domain_details` — and explicitly deferred a generic escape
hatch. This spec defines that escape hatch: `run_explore_query`, a single tool that lets Claude
run arbitrary aggregations against the existing Explore engine (`ExploreQueryBuilder`) for
questions no specific tool covers (e.g. "average renewal price by registrar over the last
quarter for tld example").

A sibling task (`docs/superpowers/specs/<date>-registry-dash-ai-tier3-tools-batch2.md`, in flight
in the `swordfish` worktree) adds 3–5 more *specific* tools. Both PRs are independent but conflict
mechanically on `AiToolRegistry`'s constructor and the `ai.prompts.toolsHeader` config field.

## Goals

- Give Claude a safe way to run aggregations the specific tools can't express.
- Keep the schema constrained enough that Claude doesn't hallucinate fields.
- Make caps and timeouts tunable via config (no code change to retune).
- Keep the runtime cost of misuse bounded (row cap, statement timeout, TLD scope check).

## Non-goals

- Mutation. Tier 3 is read-only.
- Exposing raw SQL. The descriptor is the public contract.
- Drift-detection CI between nomulus and nomulus-secrets `default-config.yaml`. Out of scope; a
  flagged separate follow-up.
- Consolidating tool audit logging (per-tool descriptor in the orchestrator's `toolsUsed` line).
  Deferred to a follow-up after both this PR and the sibling specific-tools PR merge — see
  `bozeman-v1/.context/tier3-followup-extended-tool-audit-logging-prompt.md`.

## Schema decision (Option A — constrained per-data-source wrapper)

The tool's input schema mirrors the structure of `ExploreQueryDescriptor` but exposes only the
fields Claude needs to specify, scoped to a single TLD:

```jsonc
{
  "data_source": "REVENUE",                // one of 7 enum values; required
  "tld": "example",                        // single TLD; required
  "start_date": "2026-04-01",              // ISO date or datetime; required
  "end_date": "2026-04-30",                // ISO date or datetime; required
  "metrics": ["amount"],                   // non-empty array of metric names; required
  "dimensions": ["registrar", "operation"],// optional
  "registrar_ids": [],                     // optional
  "operations": [],                        // optional
  "activity_types": []                     // optional
}
```

Server-side validation is provided for free by `ExploreDataSource.validate()` (lines 86–127),
which already throws `IllegalArgumentException` on unknown metrics/dimensions/filters per source.
The tool catches that and rethrows as `AiToolException` so Claude sees a useful error.

We considered Option B (full descriptor minus granularity/limit) and Option C (raw pass-through);
both were rejected because Option A's per-source allowlists make the matrix small enough to fit in
`description()`, where Claude reads it to decide whether/how to use the tool.

## Guardrails (all enforced)

1. **TLD scope check.** `ToolJpaHelper.assertTldAccess(user, tld)` throws for non-admin users
   whose access scope doesn't include the requested TLD. Admins (`GlobalRole.FTE`) bypass.
2. **Row cap.** `ai.tools.maxRows` (default 500). Hardcaps the tool wrapper; the descriptor's own
   server-side cap of 10,000 rows is a backstop.
3. **SQL `statement_timeout`.** `ai.tools.statementTimeoutSeconds` (default 30). Applied via
   `SET LOCAL statement_timeout` inside the JPA transaction. On exceedance, the underlying
   `PSQLException` (SQLSTATE 57014) is converted to a user-visible
   `AiToolException("Query exceeded Ns — try a narrower date range or smaller scope.")`.
4. **No raw SQL.** The tool only takes a descriptor; SQL is built by `ExploreQueryBuilder`.
5. **PII.** Per ICANN thin-registry policy, registrant contact data is not modeled in the registry
   and therefore not reachable through the Explore engine. `domain_name` (TRANSACTIONS) is public
   record and explicitly OK to expose. The existing `query_transfers` tool already exposes the
   same surface.
6. **Audit log.** `RunExploreQueryTool.execute` emits a structured log line via FluentLogger
   capturing user, dataSource, tld, dimensions, metrics, filter args, and date range. Separate
   from the orchestrator's `toolsUsed=[...]` line; consolidation is the deferred follow-up.

## description() lead-in

The tool's `description()` opens with a tie-breaker so Claude knows to prefer specific tools:

> "Use this only when no specific tool covers the question. Reach for query_transfers,
> get_pricing_rules, query_registrar_activity, query_domain_details first."

Followed by an enumerated list of all 7 data sources × their valid metrics/dimensions, derived
from `ExploreDataSource.getAllowedMetrics()` and `getAllowedDimensions()` (so the description
stays in sync if the enum changes).

The system prompt's `ai.prompts.toolsHeader` is also updated to mention the same tie-breaker — a
second nudge at a different layer.

## Configuration

Two new config knobs added under `ai.tools` in `default-config.yaml`:

```yaml
ai:
  tools:
    maxRows: 500
    statementTimeoutSeconds: 30
```

These are surfaced via `RegistryConfigSettings.Ai.Tools` (a new nested class) and bound via
`@Provides @Config("aiToolsMaxRows")` / `@Config("aiToolsStatementTimeoutSeconds")` in
`RegistryConfig`. The same values must be mirrored to `nomulus-secrets/default-config.yaml`
(separate follow-up PR; see `bozeman-v1/.context/nomulus-secrets-tier3-toolsheader-prompt.md`
pattern).

## Test scope

- New: `RunExploreQueryToolTest` — schema validation, missing/empty args, unknown data_source,
  invalid metric/dimension per source, TLD permission denial. Description and inputSchema smoke.
- Backfill: `QueryTransfersToolTest`, `GetPricingRulesToolTest`,
  `QueryRegistrarActivityToolTest`, `QueryDomainDetailsToolTest` — same pattern. Establishes
  test coverage for tools that previously had none.
- Shared: `AiToolTestBase` — JPA extension, fake clock, user-fixture helpers (`createFteUser`,
  `createRoUser`, `mapUserToTld`), `assertAiToolException`.

Happy-path tests with real fixture data (BillingEvent rows, DomainHistory rows) are deferred —
the safety net is permission + arg validation; happy-path is best exercised by the local-stack
E2E in `test-registry-dash`.

## Verification

- `./gradlew :core:compileJava :core:compileTestJava` — clean.
- `./gradlew :core:test --tests "google.registry.ai.tools.*"` — all new + backfilled tests pass.
- Frontend build: `cd console-webapp && nvm use 22.16.0 && npm run build`.
- Local E2E: ask the AI modal "what's our average renewal price by registrar over the last
  quarter for tld example?" — confirm `🔬 Running data query` chip appears and the response
  cites real rows. Negative test: ask "show me transfers for tld example last week" — confirm
  Claude picks `query_transfers`, NOT the generic.

## Coordination

- **Sibling PR (specific tools, swordfish worktree):** mechanical conflict on
  `AiToolRegistry`'s constructor. Whichever PR merges second adds the missing constructor
  param. The sibling adds tools after `queryDomainDetails`; we add `runExploreQuery` at the
  end. Order of params doesn't affect runtime behavior.
- **nomulus-secrets PR:** opened after this PR merges. Mirrors the new `ai.tools` block and
  `ai.prompts.toolsHeader` to the deployed config. Pattern established by
  `nomulus-secrets-tier3-toolsheader-prompt.md`.
- **Drift CI:** flagged as a follow-up; surface in this PR's body.
