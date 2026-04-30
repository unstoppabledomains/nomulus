# TDD Plan — Tier 3 Tools Batch 2

**Date:** 2026-04-30
**Spec:** `docs/superpowers/specs/2026-04-30-registry-dash-ai-tier3-tools-batch2.md`
**Tracking:** SRE-1941

Each phase is one commit. Within a phase: write the test, watch it fail, write the implementation, watch it pass, refactor, commit. The test patterns mirror `RegistryDashPricingActionTest` (uses `JpaTestExtensions.JpaIntegrationTestExtension`, `RoRegistry` mapping helpers, FTE-vs-non-RO `User` fixtures).

## Phase 1: `GetRegistrarDetailsTool`

**Test cases** (`GetRegistrarDetailsToolTest.java`):

1. `execute_admin_returnsFullProfile` — FTE user; persisted `Registrar` with `Type.REAL`, `State.ACTIVE`, `allowedTlds={"tld"}`; expects all fields populated.
2. `execute_mappedNonAdmin_returnsFullProfile` — non-admin user mapped via RoRegistry to "registrar1"; expects success.
3. `execute_unmappedNonAdmin_throwsPermissionDenied` — non-admin user with no mapping; `AiToolException` with "Permission denied for registrar".
4. `execute_unknownRegistrar_returnsErrorJson` — admin asking for nonexistent ID; returns `{error: "Registrar not found: ..."}`.
5. `execute_missingArg_throws` — `args = {}`; `AiToolException` "Missing required arg: registrar_id".

**Implementation:**
- Permission gate: admin bypass; otherwise `RegistryDashAccessUtil.getMappedRegistrarIds(user.email).contains(registrarId)`.
- Load via `Registrar.loadByRegistrarId(id)`; if absent, return `{error: ...}`.
- Build JSON with `registrar_id`, `registrar_name`, `type`, `state`, `iana_identifier`, `allowed_tlds`, `email_address`, `phone_number`, `fax_number`, `whois_server`, `rdap_base_urls`, `contacts[]` (each: `name`, `email`, `phone`, `types`).
- Wire into `AiToolRegistry`.
- Frontend: `get_registrar_details: '🏢 Looking up registrar'`.

## Phase 2: `GetTldConfigTool`

**Test cases:**

1. `execute_admin_returnsConfig` — FTE user; `Tld` with state, currency, premium/reserved list names; expects all fields.
2. `execute_mappedNonAdmin_returnsConfig` — non-admin user mapped to "tld" via RoRegistry; expects success.
3. `execute_unmappedNonAdmin_throwsPermissionDenied` — non-admin asking about TLD not in mapping; `AiToolException`.
4. `execute_unknownTld_returnsErrorJson` — admin asking for nonexistent TLD; returns `{error: ...}`.
5. `execute_missingArg_throws`.
6. `execute_allowedRegistrars_filteredAndCapped` — 101 registrars with `allowedTlds={"tld"}`; expects 100 in payload + `allowed_registrars_truncated: true`.

**Implementation:**
- Permission gate: `ToolJpaHelper.assertTldAccess(user, tld)`.
- Load `Tld` via `Tld.get(tld)`; if absent, return `{error: ...}`.
- Resolve `tldState` via `tld.getTldState(clock.nowUtc())` — needs a `Clock`.
- Build JSON with current state, currency, premium/reserved list names, dnsWriters.
- Allowed registrars: `RegistryDashAccessUtil.getRegistrarIdsForTlds(ImmutableSet.of(tld))`, then `Registrar.loadByRegistrarId` for each, cap at 100. Set `allowed_registrars_truncated`.
- Inject `Clock` (constructor; tests use `FakeClock`).
- Wire into `AiToolRegistry`.
- Frontend: `get_tld_config: '⚙️ Looking up TLD config'`.

## Phase 3: `QueryRevenueBreakdownTool`

**Test cases:**

1. `execute_admin_groupByOperation_returnsRows` — FTE user; seed billing events; expects rows with operation grouping.
2. `execute_admin_groupByPeriod_returnsRows` — same seed; expects rows with period dimension.
3. `execute_unmappedNonAdmin_throwsPermissionDenied`.
4. `execute_dateRangeOver2Years_throws` — `start=2020-01-01, end=2023-01-01`; `AiToolException` with "Date range exceeds 2-year cap".
5. `execute_invalidGroupBy_throws` — `group_by=registrar` (or any non-allowed value); `AiToolException`.
6. `execute_missingArg_throws` — variants for each required arg.

**Implementation:**
- Permission gate: `ToolJpaHelper.assertTldAccess`.
- Validate `group_by` ∈ {`operation`, `period`}.
- Validate date range ≤ 2 years (use `LocalDate.parse` + `Period.between`).
- Build descriptor: `metrics=[amount, netAmountToRegistry]`, `dimensions=[group_by]`, `filters={tlds:[tld], dateRange}`.
- `ToolJpaHelper.runExplore(REVENUE, desc, effectiveTlds, columns, 100)`.
- Wire + frontend label.

## Phase 4: `QueryRenewalRatesTool`

**Test cases:**

1. `execute_admin_returnsRows` — FTE user; seed events; expects rows.
2. `execute_unmappedNonAdmin_throwsPermissionDenied`.
3. `execute_missingArg_throws` — variants.

**Implementation:**
- Permission gate: `ToolJpaHelper.assertTldAccess`.
- Build descriptor: `metrics=[renewals, deletions, renewalRate]`, `dimensions=[tld]`, `filters={tlds:[tld], dateRange}`.
- `runExplore` with `MAX_ROWS = 100`.
- Wire + frontend label.

## Phase 5: `QueryExpirationCurveTool`

**Test cases:**

1. `execute_admin_returnsRows` — FTE user; expect month-bucketed rows.
2. `execute_unmappedNonAdmin_throwsPermissionDenied`.
3. `execute_monthsAheadOutOfRange_clamps` — `months_ahead=0` → bumped to 1; `months_ahead=120` → clamped to 60. Verify by checking the dateRange filter we build.
4. `execute_missingArg_throws`.

**Implementation:**
- Permission gate.
- Clamp `months_ahead` to [1, 60].
- Compute `start = clock.now()`, `end = start + months_ahead months`.
- Build descriptor: `metrics=[count]`, `dimensions=[month]`, `filters={tlds:[tld], dateRange}`.
- Inject `Clock`.
- `runExplore` with `MAX_ROWS = 100`.
- Wire + frontend label.

## Phase 6: Test plan + verification

1. Append 5 entries to `.claude/plugins/ud-registry-dash/skills/test-registry-dash/test-plan.md`.
2. `./gradlew :core:compileJava :core:compileTestJava`
3. `./gradlew :core:test --tests "google.registry.ai.tools.*"`
4. `cd console-webapp && nvm use 22.16.0 && npm run build`
5. Local E2E if stack is available.

## Phase 7: PR

1. Re-check `rabbit` and `hornbill` worktrees for committed changes; rebase if needed.
2. Open PR against `master`. Title: `feat(registry-dash): AI Tier 3 batch 2 — registrar/tld/revenue/renewal/expiration tools`. Link SRE-1941.
3. Update memory file `project_tier3_batch2_coordination.md` once any of the three lands.
