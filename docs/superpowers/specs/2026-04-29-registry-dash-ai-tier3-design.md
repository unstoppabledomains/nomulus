# Registry Dashboard AI — Tier 3 Design (Agentic Tool Use)

**Date:** 2026-04-29
**Author:** torrey@unstoppabledomains.com
**Status:** Implemented in PR — Tier 3 v1
**Predecessors:** Tier 1 (PR #114), Tier 2 (PR #121)
**Source design notes:** `docs/superpowers/specs/2026-04-24-registry-dash-ai-tiers-2-3-design.md`

## Context

Tier 1 sends Claude a static snapshot of the visible chart data and asks for analysis. Follow-up questions like "what specific domains transferred between which registrars?" fail because the detail isn't in the snapshot. Tier 3 lets Claude **call backend tools mid-conversation** to fetch the data it actually needs, using the Anthropic Messages API's tool-use feature.

The backend defines tools, Claude decides when to invoke them, the backend executes and feeds results back. The multi-turn loop runs server-side; text deltas and tool-use signals are streamed to the frontend over the existing SSE channel.

## Scope

**v1 ships 4 tools:**

| Tool | Wraps | Answers |
|---|---|---|
| `query_transfers` | `ExploreDataSource.TRANSACTIONS` filtered to operation=TRANSFER | "What domains transferred in the last 30 days for tld X?" |
| `get_pricing_rules` | `ExploreDataSource.PRICING_RULES` | "What's the current pricing for tld X?" |
| `query_registrar_activity` | `ExploreDataSource.DOMAIN_ACTIVITY` filtered by registrar | "What did registrar Y do last month?" |
| `query_domain_details` | `Domain` JPA entity + `DomainHistory` join | "Tell me about example.tld" |

**Deferred to a follow-up PR** (`run_explore_query`, `query_revenue_breakdown`, `query_renewal_rates`, `query_expiration_curve`, etc.) — see `.context/tier3-followup-specific-tools-prompt.md`.

## Architecture

### Tool registry

```
core/src/main/java/google/registry/ai/tools/
├── AiTool.java            // interface
├── AiToolRegistry.java    // Map<name, AiTool>, Guice-bound
├── QueryTransfersTool.java
├── GetPricingRulesTool.java
├── QueryRegistrarActivityTool.java
└── QueryDomainDetailsTool.java
```

`AiTool`:
```java
public interface AiTool {
  String name();
  String description();
  JsonObject inputSchema();   // Anthropic tool schema (JSON-Schema subset)
  JsonElement execute(JsonObject args, User user);
}
```

`AiToolRegistry` is a Guice-injected `Map<String, AiTool>` populated via `@IntoSet` (or an explicit `Multibinder`-style provider in `AnthropicModule`). Lookup by name; missing tool → `IllegalArgumentException` surfaced as a tool-use failure to Claude (the orchestrator turns it into a tool_result with `is_error: true`).

### Multi-turn orchestrator

`AiOrchestrator.run(systemPrompt, history, model, tools, eventSink)`:

1. Send the conversation + tools list to Anthropic via `AnthropicClient`.
2. As deltas stream in, emit `text` SSE events.
3. When a `tool_use` block lands: emit a `tool_use` SSE event, call `registry.get(name).execute(args, user)`, emit a `tool_result` SSE event, append both as new conversation turns, send the request again.
4. Repeat until Claude returns `stop_reason = "end_turn"` (or hits the **5-turn cap**).
5. Emit `done`.

### Anthropic client extension

`AnthropicClient.streamMessage` gains an overload accepting `List<JsonObject> tools` and a richer callback that surfaces both text deltas and `tool_use` blocks. The current 4-arg overload remains and just forwards to the new one with `tools = List.of()`.

### SSE event shape

Frontend receives JSON-per-event:

```
data: {"type":"text","text":"Looking at the transfer activity..."}
data: {"type":"tool_use","tool":"query_transfers","args":{"tld":"example","start_date":"2026-03-29","end_date":"2026-04-29"}}
data: {"type":"tool_result","tool":"query_transfers","ok":true}
data: {"type":"text","text":"In the last 30 days, 3 domains transferred..."}
data: {"type":"done"}
```

(Result payload not sent in `tool_result` — only the boolean `ok`. Keeps the SSE stream cheap; result content is already baked into the next `text` from Claude.)

## Backend Design

### Permission model

Every tool **must** verify the user can read the data it returns:

- TLD-scoped tools: intersect requested TLD against `RegistryDashAccessUtil.getMappedTlds(user.email)` for non-admins (admins bypass per the existing pattern).
- Registrar-scoped tools: same, against the user's registrar mapping.
- `query_domain_details`: looks up the domain's TLD, then runs the same TLD scope check.

Reuse `RegistryDashAccessUtil.applyFilter(accessScope, requestedFilter, isAdmin)` already in the registrydash codebase.

### Rate limiting

Folded into the existing per-user-per-hour `AiRateLimiter` budget. A single analyze request that fires 3 tool calls counts as 1 budget unit.

### Logging

Existing `logger.atInfo()` in `RegistryDashAiAction` extended:

```java
"AI analysis request: user=%s, page=%s, promptType=%s, model=%s, promptVersion=%s, historySize=%d, toolsUsed=%s"
```

`toolsUsed` = ordered list of tool names invoked (empty list if none).

### Tool descriptions in prompt

The system prompt receives a new section appended from `default-config.yaml`:

```yaml
ai:
  prompts:
    toolsHeader: |
      ## Tools available
      You have tools to fetch additional data. Prefer specific tools over general ones.
      Use a tool when the user's question requires data not in the snapshot above.
```

The actual tool list (names + descriptions + schemas) is sent to Anthropic via the `tools` array, NOT inlined in the system prompt — Anthropic renders them on its end.

## Frontend Design

### `ai-analysis.service.ts`

Parsing extended to handle the 3 new event types. The streamed observable now emits `{kind: 'text', text}`, `{kind: 'tool_use', tool, args}`, `{kind: 'tool_result', tool, ok}`, `{kind: 'done'}`.

### Modal component

A transient indicator appears below the streaming text when a `tool_use` event arrives:

```
🔍 Searching transfers…
```

The indicator clears on the matching `tool_result` event (matched by tool name; if multiple parallel tool calls appear the indicator shows each on its own line). Once cleared, the next text chunk continues normally.

### Tool labels

Hardcoded mapping in the modal:

```ts
const TOOL_LABELS: Record<string, string> = {
  query_transfers: '🔍 Searching transfers',
  get_pricing_rules: '💰 Looking up pricing',
  query_registrar_activity: '📊 Checking registrar activity',
  query_domain_details: '🔎 Looking up domain',
};
```

## Data Payloads

### `query_transfers`

Args:
```json
{"tld": "example", "start_date": "2026-03-29", "end_date": "2026-04-29"}
```

Returns:
```json
{
  "rows": [
    {"timestamp": "2026-04-15T10:23:00Z", "domain_name": "foo.example",
     "from_registrar": "OldRegistrar", "to_registrar": "NewRegistrar"}
  ],
  "rowCount": 1,
  "truncated": false
}
```

Capped at 100 rows; sets `truncated: true` when exceeded.

### `get_pricing_rules`

Args: `{"tld": "example", "registrar_id": "OldRegistrar"}` (registrar_id optional)

Returns: `{"rules": [{"registrar": "...", "tld": "...", "operation": "CREATE", "priceAmount": 10.00}], "rowCount": N}`

### `query_registrar_activity`

Args: `{"registrar_id": "TheRegistrar", "tld": "example", "start_date": "...", "end_date": "..."}` (tld + dates optional)

Returns: `{"rows": [{"period": "...", "tld": "...", "activity_type": "CREATE", "count": 42}], "rowCount": N, "truncated": bool}`

### `query_domain_details`

Args: `{"domain_name": "foo.example"}`

Returns:
```json
{
  "domain_name": "foo.example",
  "tld": "example",
  "current_registrar": "TheRegistrar",
  "creation_time": "2024-01-01T00:00:00Z",
  "expiration_time": "2027-01-01T00:00:00Z",
  "status_flags": ["serverHold"],
  "history": [
    {"type": "DOMAIN_CREATE", "time": "...", "registrar": "..."},
    {"type": "DOMAIN_TRANSFER_APPROVE", "time": "...", "registrar": "..."}
  ]
}
```

History capped at 50 most recent events.

## New Files

- `core/src/main/java/google/registry/ai/tools/AiTool.java`
- `core/src/main/java/google/registry/ai/tools/AiToolRegistry.java`
- `core/src/main/java/google/registry/ai/tools/QueryTransfersTool.java`
- `core/src/main/java/google/registry/ai/tools/GetPricingRulesTool.java`
- `core/src/main/java/google/registry/ai/tools/QueryRegistrarActivityTool.java`
- `core/src/main/java/google/registry/ai/tools/QueryDomainDetailsTool.java`
- `core/src/main/java/google/registry/ai/AiOrchestrator.java`
- `core/src/test/java/google/registry/ai/tools/*Test.java` (4)
- `core/src/test/java/google/registry/ai/AiOrchestratorTest.java`

## Modified Files

- `core/src/main/java/google/registry/ai/AnthropicClient.java` — add tools-aware overload
- `core/src/main/java/google/registry/ai/AnthropicModule.java` — provide `AiToolRegistry` + `AiOrchestrator`
- `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java` — call orchestrator, log `toolsUsed`
- `core/src/main/java/google/registry/config/RegistryConfigSettings.java` — `Prompts.toolsHeader` field
- `core/src/main/java/google/registry/config/files/default-config.yaml` — `ai.prompts.toolsHeader`
- `console-webapp/src/app/registry-dash/ai/ai-analysis.service.ts` — parse new SSE event types
- `console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts` — add `ToolUseEvent` etc.
- `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.{ts,html,scss}` — indicator UX

## Verification

End-to-end: `bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/start-local-stack.sh` + `bash .claude/plugins/ud-registry-dash/skills/test-registry-dash/helpers/seed-test-data.sh`. Open the analysis modal on the Domain Activity page, ask "what specific domains transferred in the last 30 days?". Confirm:

- SSE stream emits a `tool_use` event for `query_transfers`.
- Modal shows the transient "🔍 Searching transfers…" indicator that disappears when results arrive.
- Final text references actual domain names from the seeded data.
- Server log line includes `toolsUsed=[query_transfers]`.

Targeted tests: `./gradlew :core:test --tests "google.registry.ai.*"` and `--tests "google.registry.ui.server.console.registrydash.RegistryDashAiActionTest"`.

Frontend: `cd console-webapp && nvm use 22.16.0 && npm run build`.
