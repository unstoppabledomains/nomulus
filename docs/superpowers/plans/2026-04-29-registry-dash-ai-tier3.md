# Registry Dashboard AI Tier 3 — Implementation Plan

**Spec:** [`2026-04-29-registry-dash-ai-tier3-design.md`](../specs/2026-04-29-registry-dash-ai-tier3-design.md)

## Step-by-step

Each step is a logical commit. Tests are written alongside code (not strict RED-GREEN); compile-clean each step.

### 1. AiTool interface + AiToolRegistry

- **New** `core/src/main/java/google/registry/ai/tools/AiTool.java` — interface (`name`, `description`, `inputSchema`, `execute`).
- **New** `core/src/main/java/google/registry/ai/tools/AiToolRegistry.java` — wraps `Map<String, AiTool>`, provides `get(name)`, `all()`, `anthropicToolDefinitions()` (returns the JSON array Anthropic wants).
- **New** `AiToolRegistryTest.java` — register/lookup/missing.

### 2. Tool implementations

For each: implement `AiTool`, write a unit test that exercises the happy path + permission denial.

- `QueryTransfersTool` — wraps `ExploreQueryBuilder` + `ExploreDataSource.TRANSACTIONS` filtered by `operation IN ('TRANSFER')`. Caps at 100 rows.
- `GetPricingRulesTool` — wraps `ExploreQueryBuilder` + `ExploreDataSource.PRICING_RULES`.
- `QueryRegistrarActivityTool` — wraps `ExploreQueryBuilder` + `ExploreDataSource.DOMAIN_ACTIVITY`, registrar-required filter.
- `QueryDomainDetailsTool` — direct JPA: `ForeignKeyUtils.loadResourceByCacheIfEnabled(Domain.class, name, time)` + native query for `DomainHistory`. Caps at 50 history events.

All four reuse `RegistryDashAccessUtil.applyFilter` for TLD scoping.

### 3. Extend AnthropicClient

- Add `streamMessage` overload accepting `List<JsonObject> tools` and a `Consumer<StreamEvent>` callback.
- `StreamEvent` is a tagged union: `TextDelta`, `ToolUseBlock`, `MessageStop`.
- The existing 4-arg overload forwards to the new one with `tools=List.of()`.
- Test: feed canned SSE responses (text-only, tool-use, mixed) and assert callback invocations.

### 4. AiOrchestrator

- **New** `core/src/main/java/google/registry/ai/AiOrchestrator.java` — `run(systemPrompt, history, model, tools, registry, user, sink)`.
- 5-turn cap. On `tool_use`, call `registry.get(name).execute(args, user)`, append `tool_result` block, recurse.
- Test: orchestrator with stubbed `AnthropicClient` returning canned tool-use → text sequences.

### 5. Wire into RegistryDashAiAction

- Inject `AiOrchestrator` + `AiToolRegistry`.
- Replace direct `streamMessage` call with `orchestrator.run(...)`.
- Extend log line with `toolsUsed=%s` (tracked via the orchestrator's recorded tool list).
- New SSE event types written to `PrintWriter`: `text`, `tool_use`, `tool_result`, `done`.

### 6. Guice wiring

- `AnthropicModule.provideAiToolRegistry(...)` registers all 4 tools.
- `AnthropicModule.provideAiOrchestrator(client, registry)`.

### 7. Config

- `RegistryConfigSettings.Prompts.toolsHeader` (String).
- `default-config.yaml ai.prompts.toolsHeader: "..."`.
- `buildSystemPrompt` appends `toolsHeader` after `responseGuidance` when non-empty.

### 8. Frontend

- `ai-analysis.models.ts` — add `StreamEvent` discriminated union.
- `ai-analysis.service.ts` — parse new SSE event types, emit through observable.
- `ai-analysis-modal.component.ts/.html/.scss` — render in-flight tool indicator.

### 9. Skill update

- `.claude/plugins/ud-registry-dash/skills/test-registry-dash/test-plan.md` — add Tier 3 test cases.

### 10. Verify + PR

- `./gradlew :core:compileJava :core:compileTestJava`
- `./gradlew :core:test --tests "google.registry.ai.*"`
- `cd console-webapp && nvm use 22.16.0 && npm run build`
- Local E2E if helpers run cleanly.
- Open PR against `master` referencing #114 and #121.

## Out of scope

- `run_explore_query` — see `.context/tier3-additional-tools-prompt.md` for follow-up.
- Per-tool rate limiting — folded into existing analyze budget.
- BigQuery audit export — log-line only for v1.
- Mutation tools (would need separate confirmation UX).
