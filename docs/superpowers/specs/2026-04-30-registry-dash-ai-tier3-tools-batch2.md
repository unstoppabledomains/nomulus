# Registry Dashboard AI — Tier 3 Tools Batch 2

**Date:** 2026-04-30
**Author:** torrey@unstoppabledomains.com
**Status:** Implementing
**Tracking:** SRE-1941
**Predecessor:** Tier 3 v1 (PR #122) — see `2026-04-29-registry-dash-ai-tier3-design.md`
**Source prompt:** `.context/tier3-followup-specific-tools-prompt.md`

## Context

Tier 3 v1 shipped 4 AI tools (`query_transfers`, `get_pricing_rules`, `query_registrar_activity`, `query_domain_details`). This batch adds 5 more tools that wrap existing query infrastructure. The orchestrator, `AiTool` interface, `ToolJpaHelper`, SSE event flow, and frontend wiring are unchanged — this is additive.

## Scope

| Tool | Wraps | Answers |
|---|---|---|
| `get_registrar_details` | `Registrar` JPA entity | "Tell me about registrar X" |
| `get_tld_config` | `Tld` JPA entity + `RegistryDashAccessUtil.getRegistrarIdsForTlds` | "How is TLD example configured?" |
| `query_revenue_breakdown` | `ExploreDataSource.REVENUE` | "Which registrar made us the most money last quarter?" |
| `query_renewal_rates` | `ExploreDataSource.RENEWAL_RATES` | "What's the renewal rate for TLD example?" |
| `query_expiration_curve` | `ExploreDataSource.EXPIRATION_CURVE` | "How many domains expire in the next 12 months?" |

Each tool ships as its own commit (TDD: test first, then implementation), with frontend `TOOL_LABELS` updated and `AiToolRegistry` constructor extended.

## Permission model

Reuses Tier 3 v1's pattern verbatim (`ToolJpaHelper.assertTldAccess` for TLD-scoped tools). The one new permission shape is `get_registrar_details`, which is registrar-scoped:

```
isAdmin = user.userRoles.globalRole == FTE
if !isAdmin:
  mappedRegistrarIds = RegistryDashAccessUtil.getMappedRegistrarIds(user.email)
  if registrar_id not in mappedRegistrarIds:
    throw AiToolException("Permission denied for registrar: ...")
```

`get_tld_config` is TLD-scoped on the requested `tld`. The "allowed registrars" list it returns is also filtered to the user's access via `RegistryDashAccessUtil.getRegistrarIdsForTlds(ImmutableSet.of(tld))` — no separate check needed since the helper already scopes through `RoRegistry`.

**PII decision:** Registrar contacts and addresses are returned as-is. The access check (`getMappedRegistrarIds`) ensures only users mapped to the registrar can see it; this is an internal tool and trusting the access check is consistent with the rest of the registry-dashboard surface.

## Output caps

- All Explore-engine tools: `MAX_ROWS = 100`, returns `{rows, rowCount, truncated}` via `ToolJpaHelper.runExplore`.
- `get_registrar_details`: returns single object (no row cap needed); contacts list is bounded by the entity itself.
- `get_tld_config`: allowed-registrars list capped at 100 entries (sets `allowedRegistrarsTruncated: true` if exceeded).
- `query_revenue_breakdown`: rejects date ranges > 2 years.
- `query_expiration_curve`: clamps `months_ahead` to [1, 60].

## Tool specs

### `get_registrar_details`

**Args:** `{ registrar_id: string (required) }`

**Returns:**
```json
{
  "registrar_id": "TheRegistrar",
  "registrar_name": "The Registrar",
  "type": "REAL",
  "state": "ACTIVE",
  "iana_identifier": 9999,
  "allowed_tlds": ["example", "test"],
  "email_address": "abuse@example.com",
  "phone_number": "+1.5551234567",
  "fax_number": null,
  "whois_server": "whois.example.com",
  "rdap_base_urls": ["https://rdap.example.com/"],
  "contacts": [
    {"name": "Abuse Contact", "email": "abuse@example.com",
     "phone": "+1.5551234567", "types": ["ABUSE"]}
  ]
}
```

### `get_tld_config`

**Args:** `{ tld: string (required) }`

**Returns:**
```json
{
  "tld": "example",
  "tld_state": "GENERAL_AVAILABILITY",
  "currency": "USD",
  "premium_list_name": "example_premium",
  "reserved_list_names": ["example_reserved"],
  "dns_writers": ["VoidDnsWriter"],
  "allowed_registrars": [
    {"registrar_id": "TheRegistrar", "registrar_name": "The Registrar"}
  ],
  "allowed_registrars_count": 1,
  "allowed_registrars_truncated": false
}
```

### `query_revenue_breakdown`

**Args:** `{ tld: string (required), start_date: string (required), end_date: string (required), group_by: "registrar"|"operation"|"period" (required) }`

**Validation:** `(end_date - start_date) <= 2 years`. Reject longer ranges with `AiToolException`.

**Implementation:** Builds an `ExploreQueryDescriptor` for `REVENUE` with:
- `metrics = [amount, netAmountToRegistry]`
- `dimensions` chosen by `group_by`:
  - `registrar` → not a dimension on REVENUE; downgrade by joining via `tld` and using `operation` plus a per-registrar split. Verify against `ExploreDataSource.REVENUE.allowedDimensions` = `{tld, operation, period}`. **`registrar` is not allowed** — see "Open question" below.
  - `operation` → `[operation]`
  - `period` → `[period]`

**Returns:** `{rows: [{...}], rowCount, truncated}`.

### `query_renewal_rates`

**Args:** `{ tld: string (required), start_date: string (required), end_date: string (required) }`

**Implementation:** Builds an `ExploreQueryDescriptor` for `RENEWAL_RATES`:
- `metrics = [renewals, deletions, renewalRate]`
- `dimensions = [tld]`
- `filters = {tlds: [tld], dateRange: {start, end}}`

**Returns:** `{rows: [{tld, renewals, deletions, renewalRate}], rowCount, truncated}`.

### `query_expiration_curve`

**Args:** `{ tld: string (required), months_ahead: integer (required, clamped to [1, 60]) }`

**Implementation:** Computes `start_date = today`, `end_date = today + months_ahead months`, then builds an `ExploreQueryDescriptor` for `EXPIRATION_CURVE`:
- `metrics = [count]`
- `dimensions = [month]`
- `filters = {tlds: [tld], dateRange}`

**Returns:** `{rows: [{month, count}], rowCount, truncated}`.

## Open question (resolved during implementation)

`ExploreDataSource.REVENUE` declares `allowedDimensions = {tld, operation, period}` — no `registrar`. The prompt's `query_revenue_breakdown(group_by=registrar)` cannot be served by the existing Explore engine without extending `REVENUE`. **Resolution:** map `group_by=registrar` to a separate code path that runs a custom SQL via `ToolJpaHelper.runExplore`'s pattern, OR drop `registrar` from the `group_by` enum. We choose to drop it for v2 and document it as a future extension in the implementation plan, keeping `group_by` ∈ {operation, period} only. This keeps the PR scope tight and avoids touching `ExploreDataSource`.

## Frontend

`console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts` `TOOL_LABELS`:

```ts
get_registrar_details: '🏢 Looking up registrar',
get_tld_config: '⚙️ Looking up TLD config',
query_revenue_breakdown: '💵 Breaking down revenue',
query_renewal_rates: '🔄 Checking renewal rates',
query_expiration_curve: '📉 Mapping expirations',
```

## New files

- `core/src/main/java/google/registry/ai/tools/GetRegistrarDetailsTool.java`
- `core/src/main/java/google/registry/ai/tools/GetTldConfigTool.java`
- `core/src/main/java/google/registry/ai/tools/QueryRevenueBreakdownTool.java`
- `core/src/main/java/google/registry/ai/tools/QueryRenewalRatesTool.java`
- `core/src/main/java/google/registry/ai/tools/QueryExpirationCurveTool.java`
- `core/src/test/java/google/registry/ai/tools/GetRegistrarDetailsToolTest.java`
- `core/src/test/java/google/registry/ai/tools/GetTldConfigToolTest.java`
- `core/src/test/java/google/registry/ai/tools/QueryRevenueBreakdownToolTest.java`
- `core/src/test/java/google/registry/ai/tools/QueryRenewalRatesToolTest.java`
- `core/src/test/java/google/registry/ai/tools/QueryExpirationCurveToolTest.java`

## Modified files

- `core/src/main/java/google/registry/ai/tools/AiToolRegistry.java` — add 5 constructor params + append to `ImmutableList.of(...)`.
- `console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts` — add 5 `TOOL_LABELS` entries.
- `.claude/plugins/ud-registry-dash/skills/test-registry-dash/test-plan.md` — add 5 new E2E tests.
- `docs/superpowers/specs/2026-04-29-registry-dash-ai-tier3-design.md` — fix dangling reference to `tier3-additional-tools-prompt.md` (rename to `tier3-followup-specific-tools-prompt.md`).

## Coordination

Sibling worktrees in plan mode (no code yet) that will conflict on `AiToolRegistry`:
- **rabbit** (`UDtorrey/tier3-followup-explore-add-to-chat`): "Add to AI Chat" feature.
- **hornbill**: generic `run_explore_query` tool.

Re-check both before opening PR; rebase mechanically if either lands first. Conflict surface is the `@Inject` constructor param list and the `ImmutableList.of(...)` body.

## Verification

- `./gradlew :core:compileJava :core:compileTestJava`
- `./gradlew :core:test --tests "google.registry.ai.tools.*"`
- Frontend: `cd console-webapp && nvm use 22.16.0 && npm run build`
- Local E2E: start-local-stack + seed-test-data, fire 5 analyses (one per new tool), confirm `toolsUsed=[…]` in server log.

## Anti-scope

- No mutation tools.
- No external service calls.
- No `default-config.yaml` change.
- No new `ExploreDataSource` enum values or new dimensions/metrics on existing sources.
