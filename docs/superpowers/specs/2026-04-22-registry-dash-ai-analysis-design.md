# Registry Dashboard AI Analysis — Tier 1 Design Spec

## Context

The registry dashboard provides charts and tables across Domain Activity, Revenue Billing, Forecasting, and other pages. Users currently interpret data manually. This feature adds AI-powered analysis via Claude — predefined analysis prompts on each chart plus conversational follow-ups — to surface trends, anomalies, and actionable recommendations automatically.

## Scope

**Tier 1 only.** Three pages: Domain Activity, Revenue Billing, Forecasting. Sparkle button with predefined prompt menu, analysis modal with streaming response and follow-up input. Backend proxy to Anthropic API.

## Architecture

### Approach: Hybrid (Backend System Prompts + Frontend User Messages)

```
Angular Frontend                    Java Backend                     Anthropic API
─────────────────                   ────────────                     ─────────────
User clicks ✨ →                    
Picks prompt from menu →           
AiAnalysisService builds payload → POST /console-api/registry-dash/ai/analyze
                                    → Auth check
                                    → Rate limit check (120 req/hr per user)
                                    → Load system prompt by page + promptType
                                    → Build messages array
                                    → Call Anthropic Messages API (streaming) ──→ POST /v1/messages
                                    ← SSE chunks piped back ←──────────────────── SSE stream
← Modal renders streamed markdown  
User types follow-up →             
Append to conversationHistory →     → Same flow, with history
```

### Dev vs Production Prompt Strategy

- **Development** (`RegistryEnvironment != PRODUCTION`): System prompts editable live in the UI. The analysis modal includes a collapsible "Advanced" section (FTE/admin only) with a textarea showing the current system prompt. Edit the prompt, hit Send, see results immediately. Edited prompt sent as `systemPrompt` field — backend passes through. Default prompts loaded from Angular constants but freely editable per-session.
- **Production** (`RegistryEnvironment == PRODUCTION`): System prompts stored in backend config (YAML or DB). Backend ignores frontend `systemPrompt` field. Loaded by `page` + `promptType` key. Hot-reloadable without deploy if DB-backed.
- **Migration path**: Iterate on prompts in the UI → copy finalized prompts to backend config → backend takes over.

## Frontend Design

### Sparkle Button

- `✨ Analyze` button per-chart inside each tab's template, in the chart header bar
- Reusable `AiSparkleButtonComponent` (standalone Angular component)
- Clicking opens a `MatMenu` dropdown with 2-3 page-specific prompt options
- **Note:** Revenue Billing and Forecasting are tabs within `financials.component.ts`, not top-level pages. The sparkle button goes in each tab's own component template (`revenue-billing.component.ts`, `forecasting.component.ts`).

### Prompt Menu (per page)

**Domain Activity:**
- 📊 Summarize trends — lifecycle patterns, growth/decline
- 🔍 Find anomalies — spikes, unusual create/delete ratios
- 💡 Suggest actions — retention, growth opportunities

**Revenue Billing:**
- 📊 Summarize trends — revenue drivers, growth percentages
- 🔍 Find anomalies — spikes, declining segments
- 💡 Suggest actions — pricing adjustments, registrar outreach

**Forecasting:**
- 📊 Summarize trends — renewal health overview
- ⚠️ Identify risks — expiration cliffs, declining registrars
- 💡 Suggest actions — retention strategies, pricing recommendations

### Analysis Modal

Uses `MatDialog` (same pattern as existing `DrillDownDialogComponent`).

**Header:**
- Title (e.g., "Revenue Trend Analysis")
- Context line: model name, active filters, date range
- Model switcher: segmented toggle (Haiku / **Sonnet** / Opus)
- Close button

**Body:**
- Streamed markdown rendered incrementally
- Scrollable for longer responses

**Advanced Section (FTE/admin only):**
- Collapsible, hidden by default
- Textarea showing the current system prompt for this page + promptType
- Editable — changes apply to the next request only (not persisted)
- Default value loaded from Angular prompt constants

**Footer:**
- Text input: "Ask a follow-up question..."
- Send button
- Follow-ups append to conversation history and re-call the same endpoint

### Model Preference

- Default model stored per user via existing `/console-api/registry-dash/settings` endpoint
- Model override available in the modal header (per-request)
- Resolution order: request override → user setting → server default (sonnet)
- Three options: Haiku (fast/cheap), Sonnet (default/balanced), Opus (deep analysis)

## Data Payloads

### Common Request Shape

```typescript
interface AiAnalyzeRequest {
  page: 'domain-activity' | 'revenue-billing' | 'forecasting';
  promptType: string;       // e.g., 'summarize_trends', 'find_anomalies', 'suggest_actions'
  metadata: {
    dateRange: { start: string; end: string };
    granularity?: string;
    filteredTlds: string[];
    filteredRegistrars: string[];
    [key: string]: any;     // page-specific metadata
  };
  chartData: any;           // page-specific, matches existing API response shape
  model?: string;           // 'haiku' | 'sonnet' | 'opus'
  systemPrompt?: string;    // dev only — ignored in production
  conversationHistory: ConversationMessage[];
}

interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}
```

### Page-Specific chartData

**Domain Activity** — `{ activity: [{period, tld, type, count}], currentCounts: {tld: count} }`

**Revenue Billing** — `{ periodRevenue: [{period, tld, operation, amount, netAmountToRegistry}], totals: {totalRevenue, totalNetAmountToRegistry, byOperation} }`

**Forecasting** — `{ renewalRates: [{period, tld, registrarId, rate, eligibleDomains, renewedDomains}], expirationCurve: [{period, tld, expiringDomains, projectedRenewals, revenueAtRisk}] }`

All chartData shapes match what the frontend already receives from existing API endpoints — no transformation needed.

## Backend Design

### New Action: RegistryDashAiAction

- **Path:** `POST /console-api/registry-dash/ai/analyze`
- **Auth:** `@Auth(Auth.AUTH_PUBLIC_LOGGED_IN)` — same as all registry-dash endpoints
- **Permission:** `ConsolePermission.VIEW_DASHBOARD_OVERVIEW` — if a user can see the dashboard, they can use AI analysis on any page they're viewing. Page-level access is already enforced by frontend routing.
- **Location:** `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java`
- **Pattern:** Extends `ConsoleApiAction`, follows existing action class conventions
- **SSE note:** Cannot use `consoleApiParams.response().setPayload()` — must write directly to servlet `OutputStream` with manual flushing for streaming.

### AnthropicClient

- **Location:** `core/src/main/java/google/registry/ai/AnthropicClient.java`
- Uses `OkHttpClient` (same as MosApiClient pattern, see `MosApiModule` for DI template)
- API key from Google Secret Manager, secret name: `ud_rsp_anthropic_api_key`
- Default model from config, overridable per request
- Streaming support: reads response body as stream, yields chunks

### Streaming: Server-Sent Events (SSE)

- Response `Content-Type: text/event-stream`
- Java action reads chunks from Anthropic's streaming response
- Each chunk written as `data: {"text": "..."}\n\n` to servlet response
- Flush after each chunk for real-time delivery
- Angular reads via `fetch()` + `ReadableStream`, appends text to modal incrementally
- SSE chosen over WebSocket: unidirectional (server→client), works over standard HTTP, matches Anthropic's own streaming protocol. Sufficient through Tier 3.

### Rate Limiting

- In-memory counter per user email
- Default: 120 requests/hour (configurable)
- Returns HTTP 429 with `Retry-After` header when exceeded
- Resets on sliding window

### Error Handling

| Failure | Backend Response | User Sees |
|---------|-----------------|-----------|
| Rate limit exceeded | 429 + Retry-After | "Analysis limit reached. Try again in X minutes." |
| Anthropic API error (500/timeout) | 502 | "Analysis temporarily unavailable. Please try again." |
| Anthropic rate limit (429) | 503 + Retry-After | "AI service is busy. Please try again shortly." |
| Invalid API key | 500 (logged) | "Analysis service not configured. Contact admin." |
| Stream interrupted | SSE error event | Partial text shown + "Response interrupted. Try again?" |

## New Files

### Backend (Java)
- `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java`
- `core/src/main/java/google/registry/ai/AnthropicClient.java`
- `core/src/main/java/google/registry/ai/AiAnalyzeRequest.java`
- Updates to `ConsoleModule.java` — DI wiring, JSON payload provider
- Updates to `RequestComponent` routing

### Frontend (Angular)
- `console-webapp/src/app/registry-dash/ai/ai-analysis.service.ts`
- `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.ts`
- `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts`
- `console-webapp/src/app/registry-dash/ai/ai-prompts.ts` (dev phase prompt templates)
- `console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts`
- Updates to `domain-activity.component.ts` — add sparkle button
- Updates to `revenue-billing.component.ts` — add sparkle button
- Updates to `forecasting.component.ts` — add sparkle button
- Updates to `backend.service.ts` — AI analyze endpoint
- Updates to settings model — AI model preference field

## Verification

1. **Unit tests**: AnthropicClient (mock OkHttp responses), RegistryDashAiAction (auth, rate limiting, payload validation)
2. **Frontend tests**: AiAnalysisService (mock streaming responses), modal rendering, sparkle button menu
3. **Integration test**: Click sparkle on Revenue Billing → select "Summarize trends" → verify modal opens with streaming response → type follow-up → verify conversation continues
4. **Error scenarios**: Verify each error state renders the correct user-facing message
5. **Model switching**: Change model in modal, verify request uses the override; change in settings, verify persistence
6. **Manual E2E on local dev server**: Run console locally with Anthropic API key, test all 3 pages with real data
