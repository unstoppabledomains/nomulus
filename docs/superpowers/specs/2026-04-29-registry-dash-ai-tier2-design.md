# Registry Dashboard AI Analysis — Tier 2 Design Spec

## Context

Tier 1 (PR #114) shipped AI-powered analysis on three dashboard pages: Domain Activity, Revenue Billing, Forecasting. The system prompt is built in `RegistryDashAiAction.getDefaultSystemPrompt()` from hardcoded Java strings, and the user-facing prompt menu (canned "Summarize trends" entries) lives in the frontend constant file `ai-prompts.ts`.

After Tier 1 went live, the team observed:

- Two more pages already had sparkle buttons added (Overview, Explore) without a corresponding spec/plan, leaving inconsistent coverage.
- Two pages still need them (Portfolio, Pricing).
- Iterating on prompt wording requires a deploy because both the Java strings and the TS constants are baked into the build.
- There is no record of which prompt revision was active when an analysis ran, blocking quality measurement.

Tier 2 closes those gaps. Tier 3 (agentic tool use) is a separate spec.

## Scope

**In scope:**
1. Sparkle buttons on Portfolio and Pricing.
2. Re-audit existing wiring on Overview and Explore — confirm `[page]` matches a registered prompt set and the menu renders.
3. Migrate the system prompt building blocks **and** the user-message menus from hardcoded Java/TS into `default-config.yaml` under the existing `ai:` block.
4. Expose a new `GET /console-api/registry-dash/ai/prompts?page=<page>` endpoint that serves the active menu + prompt version. Frontend fetches at first sparkle-button open per page.
5. Log the active `promptVersion` on every analysis request for observability.

**Out of scope (deferred):**
- Anthropic tool_use / function calling (Tier 3).
- FTE-only prompt-version override beyond what Tier 1 already supports (the existing `systemPrompt` override in `AiAnalyzeRequest` is unchanged).
- Hash-based variant assignment / live A/B experiments.
- Per-environment prompt overrides — the env-specific YAML rollout is tracked separately in `docs/superpowers/plans/2026-04-25-env-specific-ai-config.md`.
- Refining prompt wording itself — that is a content task done after the structural migration lands. Existing prompt strings carry over verbatim into YAML.

## Architecture

### Java → YAML migration

```
default-config.yaml                       RegistryConfigSettings.Ai.Prompts
─────────────────────                     ────────────────────────────────
ai:                                        public static class Prompts {
  prompts:                                   public String version;
    version: "v1"                            public String basePreamble;
    basePreamble: "You are an…"              public Map<String,String> promptTypes;
    promptTypes:                             public Map<String,String> pageHints;
      summarize_trends: "…"                  public Map<String,List<MenuItem>> menus;
    pageHints:                             }
      domain-activity: "…"
    menus:
      portfolio:
        - { promptType, label, icon, userMessage }
                                          │
                                          ▼
                                          @Config("anthropicPromptConfig") Prompts
                                          │
                                          ▼
                       ┌──────────────────┴──────────────────┐
                       ▼                                     ▼
       RegistryDashAiAction                    RegistryDashAiPromptsAction
       (build system prompt from               (GET /ai/prompts?page=<page>
        injected config; log version)           returns { version, menu })
```

The frontend stops importing `ai-prompts.ts` constants and instead asks the backend for the menu when a user opens the sparkle button on a page.

### Why YAML, not a database?

`default-config.yaml` is the existing pattern for tunable Nomulus configuration (`ai:` block at line 642 already exists for API base URL, secret name, default model, rate limit). YAML is hot-reloaded only on deploy, but iterating on prompt wording is a once-a-week cadence — fast enough. A DB-backed solution can come later if the cadence picks up. YAGNI.

### Why a separate prompts endpoint, not embedding in `/ai/analyze`?

- The frontend renders the menu before the user picks a prompt, so it needs the menu before any analysis call.
- A separate endpoint is cacheable per-session (one fetch per page-open) and keeps the analyze endpoint focused.
- Same auth gate (`VIEW_DASHBOARD_OVERVIEW`); negligible additional surface.

## Frontend Design

### Portfolio + Pricing sparkle wiring

- `<app-ai-sparkle-button page="portfolio">` placed in the page-header row of `portfolio.component.html`, matching the placement in `overview.component.html`.
- Same pattern for Pricing in `pricing.component.html`.
- Sparkle button component already exists; only the host templates change.

### Audit task for Overview + Explore

- Open `overview.component.html` and `explore.component.html`; confirm `[page]` literal matches the YAML menu key.
- Click each in local dev; confirm the menu renders the expected items (`summarize_trends`, `find_anomalies`, `suggest_actions` for both).
- No code change expected — this is a verification step that gates the Phase B migration.

### Prompts service

New service (or extension of `ai-analysis.service.ts`):

```typescript
@Injectable({ providedIn: 'root' })
export class AiPromptsService {
  private cache = new Map<string, { version: string; menu: AiPromptOption[] }>();

  async getMenu(page: string): Promise<{ version: string; menu: AiPromptOption[] }> {
    if (this.cache.has(page)) return this.cache.get(page)!;
    const res = await fetch(`/console-api/registry-dash/ai/prompts?page=${page}`, { credentials: 'include' });
    if (!res.ok) return { version: 'fallback', menu: FALLBACK_MENU[page] ?? [] };
    const data = await res.json();
    this.cache.set(page, data);
    return data;
  }
}
```

`ai-sparkle-button.component.ts` calls `getMenu(this.page)` on first open per page, populates `MatMenu` from the result. The existing `ai-prompts.ts` constants are kept as `FALLBACK_MENU` only — they're no longer the source of truth in production, but they keep the UI useful if the backend fetch fails.

### Frontend types

`AiPromptOption` already exists; the response shape adds `version` at the top level.

```typescript
interface AiPromptsResponse {
  version: string;
  menu: AiPromptOption[];
}
```

## Backend Design

### Refactored `getDefaultSystemPrompt`

Replace the hardcoded `switch` over `promptType` (currently lines 159-179 of `RegistryDashAiAction.java`) with lookups against the injected prompt config:

```java
private String getDefaultSystemPrompt(
    String page, String promptType, JsonElement chartData, JsonObject metadata) {
  StringBuilder sb = new StringBuilder();
  sb.append(promptConfig.basePreamble).append("\n\n");
  sb.append("## Analysis Type\n");
  sb.append(promptConfig.promptTypes.getOrDefault(
      promptType, "Analyze this data and provide insights.")).append("\n");
  String pageHint = promptConfig.pageHints.get(page);
  if (pageHint != null) {
    sb.append("\n## Page\n").append(pageHint).append("\n");
  }
  sb.append("\n## Context\n");
  // ...metadata block unchanged...
  sb.append("\n## Data\n```json\n").append(gson.toJson(chartData)).append("\n```\n");
  sb.append(promptConfig.responseGuidance);  // closing instructions, also config-driven
  return sb.toString();
}
```

Method signature unchanged; only the body. The constructor gets a new injected `Prompts promptConfig` parameter.

### New action: `RegistryDashAiPromptsAction`

- **Path:** `GET /console-api/registry-dash/ai/prompts`
- **Query param:** `page` — required, validated against the same allowlist as `AiAnalyzeRequest`.
- **Auth:** `Auth.AUTH_PUBLIC_LOGGED_IN`, same as `RegistryDashAiAction`.
- **Permission:** `VIEW_DASHBOARD_OVERVIEW`.
- **Response body:** `{"version": "v1", "menu": [{"promptType": "summarize_trends", "label": "...", "icon": "...", "userMessage": "..."}, ...]}`.
- **Errors:**
  - `400` if `page` missing or not in allowlist.
  - `403` if permission missing.
  - `404` if the YAML has no menu for the requested page (treated as misconfiguration).
- **Location:** `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsAction.java`.

### Page allowlist refactor

`AiAnalyzeRequest.isValid()` currently has a hardcoded set. To stay DRY between the analyze endpoint and the new prompts endpoint, extract:

```java
public static final ImmutableSet<String> VALID_PAGES = ImmutableSet.of(
    "domain-activity", "revenue-billing", "forecasting", "explore", "overview",
    "portfolio", "pricing");
```

Both actions reference `AiAnalyzeRequest.VALID_PAGES`.

### Prompt-version observability

Extend the existing `logger.atInfo()` call at `RegistryDashAiAction.java:104`:

```java
logger.atInfo().log(
    "AI analysis request: user=%s, page=%s, promptType=%s, model=%s, "
    + "promptVersion=%s, historySize=%d",
    userEmail, request.page, request.promptType, resolvedModel,
    promptConfig.version, ...);
```

Cheap, structured, log-based-metric ready. No SSE protocol change.

## Data Payloads

### `GET /ai/prompts?page=<page>` response

```json
{
  "version": "v1",
  "menu": [
    { "promptType": "summarize_trends", "label": "Summarize trends",
      "icon": "bar_chart", "userMessage": "Summarize the key trends…" },
    { "promptType": "find_anomalies", "label": "Find anomalies",
      "icon": "search", "userMessage": "Identify anomalies…" },
    { "promptType": "suggest_actions", "label": "Suggest actions",
      "icon": "lightbulb", "userMessage": "Based on this data…" }
  ]
}
```

### `default-config.yaml` schema

```yaml
ai:
  apiBaseUrl: https://api.anthropic.com
  apiKeySecretName: ud_rsp_anthropic_api_key
  defaultModel: sonnet
  rateLimitPerHour: 120
  prompts:
    version: "v1"
    basePreamble: "You are an expert domain registry analyst. You are analyzing data from a domain registry dashboard."
    responseGuidance: "Provide your analysis in clear markdown. Use specific numbers from the data. Keep your response concise and actionable."
    promptTypes:
      summarize_trends: "Summarize the key trends in this data..."
      find_anomalies: "Identify anomalies, outliers, and unusual patterns..."
      suggest_actions: "Based on this data, suggest specific actionable recommendations..."
      identify_risks: "Identify risks in this data..."
    pageHints:
      domain-activity: "(optional per-page guidance)"
      revenue-billing: "..."
      forecasting: "..."
      overview: "..."
      explore: "..."
      portfolio: "..."
      pricing: "..."
    menus:
      domain-activity: [ {promptType, label, icon, userMessage}, ... ]
      revenue-billing: [ ... ]
      forecasting: [ ... ]
      overview: [ ... ]
      explore: [ ... ]
      portfolio: [ ... ]
      pricing: [ ... ]
```

## New Files

### Backend (Java)
- `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsAction.java`
- `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsActionTest.java`

### Modified
- `core/src/main/java/google/registry/config/files/default-config.yaml` — extend `ai:` block with `prompts:`
- `core/src/main/java/google/registry/config/RegistryConfigSettings.java` — add `Prompts` nested class on `Ai`
- `core/src/main/java/google/registry/config/RegistryConfig.java` — add `@Config("anthropicPromptConfig")` provider
- `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java` — refactor `getDefaultSystemPrompt`, log promptVersion
- `core/src/main/java/google/registry/ai/AiAnalyzeRequest.java` — extract `VALID_PAGES` constant; add `portfolio`, `pricing`
- `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java` — extend to cover config-driven prompts and new pages
- `core/src/test/java/google/registry/ai/AiAnalyzeRequestTest.java` — assert `portfolio` and `pricing` accepted

### Frontend (Angular)
- `console-webapp/src/app/registry-dash/ai/ai-prompts.service.ts` (new)
- `console-webapp/src/app/registry-dash/ai/ai-prompts.service.spec.ts` (new)
- `console-webapp/src/app/registry-dash/ai/ai-prompts.ts` — convert from primary source to `FALLBACK_MENU`-only export
- `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts` — fetch menu via service instead of importing constant
- `console-webapp/src/app/registry-dash/portfolio/portfolio.component.html` — add sparkle button
- `console-webapp/src/app/registry-dash/pricing/pricing.component.html` — add sparkle button

## Verification

1. **Backend unit tests** for `Prompts` config loading and `getDefaultSystemPrompt` driven by a fixture YAML.
2. **`RegistryDashAiPromptsActionTest`** covers: 200 with menu for each valid page, 400 for missing/invalid `page`, 403 for missing permission, 404 for misconfigured page.
3. **`AiAnalyzeRequestTest`** asserts `portfolio` and `pricing` accepted, unknown pages rejected.
4. **Frontend `AiPromptsService` spec** covers: cached on second call, falls back to `FALLBACK_MENU` on fetch error, surfaces `version` in response.
5. **Local end-to-end:**
   - Start local test server, log in, navigate to Portfolio → click sparkle → menu renders → "Summarize trends" → streaming response.
   - Repeat for Pricing.
   - Re-verify Overview and Explore still work after the migration.
   - Edit `default-config.yaml` `ai.prompts.basePreamble`, restart, fire an analysis, confirm new preamble appears in server log line at INFO.
6. **Allowlist regression:** `POST /ai/analyze` with `page: "domains"` still returns 400; `page: "portfolio"` returns 200.
7. **Log-line check:** confirm `promptVersion=v1` appears in the `AI analysis request` log line on each request.
