# Registry Dashboard AI Analysis — E2E Test Plan

## Prerequisites

- Access to the Nomulus console (either local dev server at `http://localhost:4200/console` or alpha at `https://console.dnex-alpha.com/console`).
- User must have dashboard access (`VIEW_DASHBOARD_OVERVIEW` permission).
- If running locally: test server running with `ANTHROPIC_API_KEY` env var set, Angular dev server on port 4200.
- Production is **never** a valid test environment.

> **Note on prompt menu icons:** Prompts use Material icons (`bar_chart`, `search`, `lightbulb`, `warning`) rendered alongside the labels. Earlier draft language referenced emoji glyphs (📊/🔍/💡/⚠️); the implementation uses the Material equivalents.

## Test 1: Sparkle Button Visibility

**Goal:** Verify sparkle buttons appear on all 7 dashboard pages.

### Steps:
1. Navigate to **Domain Activity** (`/#/registry-dash/domain-activity`).
2. Verify two sparkle icons (`auto_awesome`) appear — one per chart ("Activity Breakdown by TLD" and "Current Domain Counts by TLD").
3. Hover over the sparkle icon — verify tooltip says "Analyze with AI".
4. Navigate to **Financials → Registry Revenue** tab.
5. Verify two sparkle icons appear on the revenue charts ("Registry Revenue by TLD", "Registry Revenue by Operation").
6. Switch to **Financials → Forecasting** tab.
7. Verify a sparkle icon appears on "Net Growth Projection". A second chart, "Domain Expirations by TLD", is conditionally rendered based on backend data availability — see [SRE-1935](https://linear.app/unstoppable-domains/issue/SRE-1935/charts-not-displaying-review-expirationcurveoptions) for the open investigation. If the second chart renders, it should also have a sparkle icon.
8. Navigate to **Overview** (`/#/registry-dash/overview`).
9. Verify three sparkle icons appear, one per chart: "Registrar Market Share", "Domain Activity Trend", "Renewal Rate by TLD".
10. Navigate to **Data Exploration** (`/#/registry-dash/explore`).
11. Before running a query, verify NO sparkle icon is visible (the button is conditional on chart data existing).
12. Configure a query (Source: Domain Activity, Metric: Count, Group By: TLD) and click "Run Query".
13. Verify a sparkle icon appears above the rendered chart.
14. Navigate to **Portfolio** (`/#/registry-dash/portfolio`).
15. Verify a single sparkle icon appears in the page header row, to the right of the "Registrar Portfolio" heading.
16. Navigate to **Pricing** (`/#/registry-dash/pricing`).
17. Verify a single sparkle icon appears in the page header row, between the "Registrar Custom Pricing Rules" heading and the "Add Rule" button.

### Expected:
- Sparkle icons visible on all 7 pages (after query runs on Explore).
- Icons are subtle (slightly transparent, opacity ~0.6) and become fully opaque on hover.
- Charts also have an "open in new" explore button alongside the sparkle (Portfolio + Pricing have only the sparkle, no explore button).

---

## Test 2: Prompt Menu

**Goal:** Verify clicking the sparkle button shows the correct prompt menu for each page.

### Steps:
1. On **Domain Activity**, click the sparkle button.
2. Verify menu shows 3 options:
   - `bar_chart` Summarize trends
   - `search` Find anomalies
   - `lightbulb` Suggest actions
3. Press Escape to close the menu.
4. Navigate to **Financials → Registry Revenue**, click sparkle. Verify the same 3 options as Domain Activity.
5. Navigate to **Financials → Forecasting**, click sparkle.
6. Verify menu shows:
   - `bar_chart` Summarize trends
   - `warning` Identify risks (NOT "Find anomalies")
   - `lightbulb` Suggest actions
7. Navigate to **Overview**, click sparkle. Verify the same 3 generic options as Domain Activity.
8. Navigate to **Portfolio**, click sparkle. Verify menu shows the same 3 generic options (`bar_chart` Summarize trends, `search` Find anomalies, `lightbulb` Suggest actions).
9. Navigate to **Pricing**, click sparkle. Verify the same 3 generic options.

### Expected:
- Each page has 3 prompt options.
- Forecasting has "Identify risks" instead of "Find anomalies".
- Menu closes when clicking outside or pressing Escape.

---

## Test 3: Analysis Modal — Initial Request

**Goal:** Verify selecting a prompt opens the modal and streams an AI response.

### Steps:
1. On **Domain Activity**, click sparkle → select "Summarize trends".
2. Verify modal opens with:
   - Title: "Summarize trends — Domain Activity"
   - Model switcher: Haiku / **Sonnet** (selected by default — unless a prior model preference is saved) / Opus
   - User message: "Summarize the key trends in domain activity..." (with person icon)
   - Sparkle icon (`auto_awesome`) next to the streaming response
   - Progress bar visible during streaming
3. Wait for the response to complete.
4. Verify the response renders as formatted markdown:
   - Headings (h1, h2, h3)
   - Bold text
   - Bullet lists / numbered lists
   - Tables (when relevant)
5. Verify the response references actual data from the charts (TLD names, counts, percentages).
6. Verify "Ask a follow-up question..." input is visible at the bottom.
7. Verify "Close" button is present.

### Expected:
- Modal opens immediately when prompt is selected.
- Text streams progressively.
- Markdown renders correctly during and after streaming.
- Progress bar disappears when streaming completes.
- Response is data-aware (references actual chart values).

---

## Test 4: Follow-Up Conversation

**Goal:** Verify follow-up questions work and maintain conversation context.

### Steps:
1. Complete Test 3 (have an analysis modal open with a completed response).
2. Click the "Ask a follow-up question..." input.
3. Type: "Which TLD has the best growth rate?"
4. Press Enter (or click the send arrow button).
5. Verify:
   - Your question appears in the conversation thread (with person icon).
   - A new streaming response begins.
   - The response demonstrates awareness of the previous analysis.
6. Ask another follow-up: "What would you recommend we focus on?"
7. Verify the conversation continues to build context.

### Expected:
- Follow-up input clears after sending.
- Input is disabled during streaming.
- Send button is disabled when input is empty or during streaming.
- Conversation scrolls to show latest messages.
- AI responses maintain context from the full conversation thread.

---

## Test 5: Model Switching

**Goal:** Verify model can be changed and preference persists.

### Steps:
1. Open a sparkle analysis on any chart (default should be Sonnet on first ever use; otherwise the last-saved model).
2. Note the response quality/speed.
3. Close the modal.
4. Open sparkle on a different chart → select any prompt.
5. Click "Opus" in the model switcher.
6. Verify the analysis runs.
7. Close the modal.
8. Navigate to a completely different page (e.g., Registry Revenue).
9. Open sparkle → select any prompt.
10. Verify the model switcher shows "Opus" as selected (preference was saved).
11. **Backend persistence check:** Open DevTools → clear `ai-model-preference` from localStorage. Reload the page, open sparkle again. Verify "Opus" is still pre-selected (loaded from the user's backend `aiModel` setting).

### Expected:
- Haiku: fastest, shortest responses.
- Sonnet: balanced (initial default).
- Opus: most detailed, slowest.
- Model preference persists across different pages and modal instances.
- Model preference survives page refresh (stored in localStorage AND backend `aiModel` setting).

---

## Test 6: Error Handling

**Goal:** Verify error states display user-friendly messages.

### Steps:
1. Open sparkle and trigger an analysis — verify it works.
2. **Backend validation:** Open DevTools → Console, run:
   ```js
   fetch('/console-api/registry-dash/ai/analyze', {
     method: 'POST', credentials: 'include',
     headers: { 'Content-Type': 'application/json' },
     body: JSON.stringify({ page: 'invalid-page', promptType: 'summarize_trends', chartData: {}, metadata: {}, conversationHistory: [] })
   }).then(r => r.text()).then(console.log);
   ```
   Verify response status is 400 with a descriptive error message ("Invalid request: page and chartData are required" or similar).
3. **Rate limiting** (impractical on alpha — 120 requests/hour). If you can trigger it locally with a lowered config: make many rapid requests and verify the modal displays "Analysis limit reached. Try again in X minutes."
4. **Backend unreachable** (local dev only): stop the test server, try to analyze, verify the modal displays "Analysis temporarily unavailable. Please try again."

### Expected:
- Error messages appear in red with an error icon.
- Follow-up input is still visible (user can retry).
- Modal doesn't crash or become unresponsive.
- Backend returns 400 for malformed requests with a descriptive message.

---

## Test 7: Registry Revenue Page

**Goal:** Verify AI analysis works correctly on financial data.

### Steps:
1. Navigate to **Financials → Registry Revenue**.
2. Click sparkle on "Registry Revenue by TLD" chart → "Summarize trends".
3. Verify the analysis discusses revenue-specific metrics (amounts, currencies, TLD names).
4. Close modal.
5. Click sparkle on "Registry Revenue by Operation" chart → "Suggest actions".
6. Verify the analysis provides actionable revenue recommendations and references operations (CREATE, RENEW, TRANSFER).

### Expected:
- Analysis references actual revenue figures from the charts (e.g., total revenue in USD).
- Different charts on the same page produce distinct analyses (TLD breakdown vs operation breakdown).

---

## Test 8: Forecasting Page

**Goal:** Verify AI analysis works on forecasting data.

### Steps:
1. Navigate to **Financials → Forecasting**.
2. Click sparkle on "Net Growth Projection" chart → "Identify risks".
3. Verify the analysis identifies expiration risks, declining patterns, projection concerns, etc.
4. Follow up with: "What retention strategies would you suggest?"
5. Verify the response is contextual and references the prior analysis.

### Expected:
- Analysis uses forecasting-specific language (renewal rates, projections, risk assessment).
- "Identify risks" prompt produces different analysis content than "Summarize trends".

> If "Domain Expirations by TLD" is visible (see [SRE-1935](https://linear.app/unstoppable-domains/issue/SRE-1935/charts-not-displaying-review-expirationcurveoptions)), repeat the test against it and confirm the analysis discusses expiration timing and TLD-level risk.

---

## Test 9: Data Exploration Page

**Goal:** Verify AI analysis works on dynamically-generated explore queries.

### Steps:
1. Navigate to **Data Exploration**.
2. Configure: Source = Domain Activity, Metric = Count, Group By = TLD, Time Range = 12m.
3. Click "Run Query".
4. After chart renders, click the sparkle button above the chart.
5. Select "Find anomalies".
6. Verify the modal title is "Find anomalies — Data Exploration".
7. Verify the analysis examines the explore query results.

### Expected:
- Sparkle button only visible when the chart has data (after running a query).
- Analysis works on dynamic explore data, not just preset dashboard views.

---

## Test 10: Overview Page

**Goal:** Verify AI analysis works on overview summary charts.

### Steps:
1. Navigate to **Overview**.
2. For each of the three charts (Registrar Market Share, Domain Activity Trend, Renewal Rate by TLD): click sparkle → "Summarize trends".
3. Verify each analysis provides a high-level overview interpretation specific to that chart.

### Expected:
- Overview analysis is strategic/summary-oriented.
- References aggregate metrics visible on the overview page (total domains, registrar count, renewal rate).
- Analyses for the three charts differ from each other (no cross-contamination).

---

## Test 11: Modal Close Behavior

**Goal:** Verify modal can be closed cleanly in all states.

### Steps:
1. Open analysis modal, wait for response to complete, click "Close" — verify modal closes.
2. Open analysis modal, while streaming is in progress, click "Close" — verify modal closes without errors.
3. Open analysis modal, click outside the modal (on the backdrop) — verify behavior matches MatDialog defaults.
4. Check browser console for any errors after closing mid-stream.
5. Immediately after a mid-stream close, open a new analysis on the same chart. Verify the new modal shows fresh content with no zombie text from the cancelled stream.

### Expected:
- No JavaScript errors in console after closing.
- No orphaned network requests or memory leaks.
- Page returns to normal state after modal closes.
- A new analysis after a mid-stream close starts cleanly.

---

## Test 12: Cross-Page Navigation

**Goal:** Verify sparkle buttons work correctly when navigating between pages.

### Steps:
1. Run analysis on Domain Activity → close modal.
2. Navigate to Registry Revenue → run analysis → close modal.
3. Navigate to Forecasting → run analysis → close modal.
4. Navigate back to Domain Activity → run analysis.
5. Verify all analyses work independently and don't interfere with each other.

### Expected:
- Each page's analysis uses that page's chart data.
- No stale data from previous pages appears in analysis.
- Model preference persists across all navigations.

---

## Test 13: Portfolio Page Analysis

**Goal:** Verify AI analysis works on registrar portfolio data (Tier 2).

### Steps:
1. Navigate to **Portfolio** (`/#/registry-dash/portfolio`).
2. Confirm the registrar table renders with at least one row (state, domain count, allowed TLDs).
3. Click the sparkle button → "Summarize trends".
4. Verify modal title is "Summarize trends — Portfolio".
5. Wait for the response. Verify it discusses portfolio composition (registrar concentration, top registrars, TLD spread, total registrar count).
6. Close the modal. Click sparkle → "Find anomalies".
7. Verify the response discusses outlier registrars or unusual TLD allocations.

### Expected:
- Analysis references concrete registrar names and domain counts visible in the table.
- "Summarize trends" and "Find anomalies" produce different content (no recycled response).
- Modal renders and streams identically to other pages.

---

## Test 14: Pricing Page Analysis

**Goal:** Verify AI analysis works on pricing data (Tier 2).

### Steps:
1. Navigate to **Pricing** (`/#/registry-dash/pricing`).
2. Confirm the pricing rules table renders with at least one row.
3. Click the sparkle button → "Summarize trends".
4. Verify modal title is "Summarize trends — Pricing".
5. Wait for the response. Verify it discusses the pricing landscape (premium spread, registrar discount distribution, TLD comparisons).
6. Close the modal. Click sparkle → "Suggest actions".
7. Verify the response provides pricing recommendations referencing actual registrar/TLD data.

### Expected:
- Analysis references concrete pricing values, registrar IDs, or TLD names from the table.
- The "Add Rule" button still works and is not obscured by the sparkle icon.

---

## Test 15: Prompts Endpoint API Smoke

**Goal:** Verify the new `GET /console-api/registry-dash/ai/prompts` endpoint serves per-page menus from backend YAML config.

### Steps:
1. From any dashboard page, open DevTools → Console.
2. For each page (`portfolio`, `pricing`, `overview`, `domain-activity`, `revenue-billing`, `forecasting`, `explore`), run:
   ```js
   await fetch('/console-api/registry-dash/ai/prompts?page=portfolio', { credentials: 'include' }).then(r => r.json())
   ```
   (substituting the page name)
3. Verify each response has shape `{ version: "v1", menu: [{promptType, label, icon, userMessage}, ...] }`.
4. Verify `menu` length is 3 for every page.
5. Verify `menu` items match the labels rendered in the UI menu (Test 2).
6. Run the request with an unknown page and confirm 400:
   ```js
   await fetch('/console-api/registry-dash/ai/prompts?page=domains', { credentials: 'include' }).then(r => r.status)
   ```
7. Run the request with no page param and confirm 400:
   ```js
   await fetch('/console-api/registry-dash/ai/prompts', { credentials: 'include' }).then(r => r.status)
   ```

### Expected:
- All 7 pages return 200 with a 3-item menu and `version: "v1"`.
- Unknown / missing page returns 400.
- The `userMessage` strings in the response match what gets sent when the user clicks a menu item (cross-check by triggering an analysis and inspecting the network request payload's `conversationHistory[0].content`).

---

## Test 16: Config-Driven System Prompt (advanced, local-dev only)

**Goal:** Verify the backend system prompt is built from `default-config.yaml` `ai.prompts` instead of hardcoded Java strings.

### Steps:
1. With the local test server running, edit `core/src/main/java/google/registry/config/files/default-config.yaml` and change `ai.prompts.basePreamble` to a sentinel value, e.g. `"PREAMBLE_SMOKE_TEST"`.
2. Restart the test server (the YAML is read at startup).
3. From the dashboard, fire any analysis (e.g. Portfolio → "Summarize trends").
4. In the test server log, find the line `AI analysis request: user=…, page=portfolio, …, promptVersion=v1, …`.
5. Optionally enable FINE logging on the action class to dump the system prompt and confirm it begins with `PREAMBLE_SMOKE_TEST`.
6. Revert the YAML change and restart.

### Expected:
- The log line includes `promptVersion=v1` (or whatever version is in the YAML).
- The change in basePreamble influences the generated system prompt without rebuilding any Java.
- After revert + restart, behavior returns to default.

> Skip this test on alpha/sandbox unless you have ops access to edit deployed config and restart pods.

---

## Test 17: Cross-Page Navigation (Tier 2 update)

**Goal:** Verify Portfolio + Pricing slot into the existing cross-page nav flow without regressions.

### Steps:
1. Run analysis on Portfolio → close modal.
2. Navigate to Pricing → run analysis → close modal.
3. Navigate to Overview → run analysis.
4. Navigate back to Portfolio → run a new analysis.
5. Verify each analysis references that page's data (registrar table for Portfolio, pricing rules for Pricing) and the model preference set in Test 5 still applies.

### Expected:
- All 7 pages now participate in the cross-page flow established by Test 12.
- No data bleed between pages.
- Model preference and conversation history are scoped per-modal (closing the modal clears history).

---

## Test 18: Tier 3 Tool Use — Indicator UX

**Goal:** Verify the analysis modal shows transient tool indicators when Claude calls a tool.

### Steps:
1. Navigate to **Domain Activity** (or any page with sparkle).
2. Click sparkle → open the analysis modal.
3. Wait for the initial analysis to finish.
4. In the follow-up box, type: `What specific domains transferred in the last 30 days for the example tld?` (substitute a TLD with seeded transfer data).
5. Watch for an inline indicator below the streaming text: `🔍 Searching transfers…` (italic, slight pulse animation).
6. The indicator should appear when the tool is in flight and disappear once the tool result arrives.
7. The final assistant text should reference specific domain names that came from the tool.

### Expected:
- Indicator appears mid-stream and is replaced by the next text chunk.
- Final answer contains specific domain names, not just chart-level summaries.
- Modal does not crash or hang.

---

## Test 19: Tier 3 Tool Use — Server Log

**Goal:** Confirm the server-side log line records `toolsUsed=` for tool-firing requests.

### Steps:
1. Tail the test server log (or Cloud Logging in alpha-gke) for `RegistryDashAiAction`.
2. Run Test 18.
3. Locate the corresponding `AI analysis request:` log line.

### Expected:
- The log line includes `toolsUsed=[query_transfers]` (or the relevant tool list).
- For analyses that don't trigger any tool, `toolsUsed=[]`.

---

## Test 20: Tier 3 Tool Use — Permission Scope

**Goal:** Tools must respect per-user TLD scope.

### Steps:
1. Log in as a non-FTE user mapped to a single registry/TLD set.
2. Open the modal on Domain Activity.
3. Ask `What domains transferred for tld <some-tld-the-user-cannot-see>?`.
4. The tool should refuse (Claude will get an `is_error: true` tool_result; the assistant will say it doesn't have access).

### Expected:
- No data from a TLD outside the user's access scope ever appears in the modal.
- Final text is a clear "I don't have access" rather than a stack trace.

---

## Test 21: Add to AI Chat from Explore

**Goal:** Verify the Data Exploration page can hand a query result off to the AI chat — either as a fresh chat or appended to an existing in-session conversation.

### Prerequisites:
- Same as Test 1 (dashboard access, local dev or alpha).
- Test data seeded so a query produces non-empty results.

### Steps — happy path "Add to current chat":
1. Navigate to **Domain Activity** (`/#/registry-dash/domain-activity`).
2. Open the AI modal via the sparkle button, choose "Summarize trends". Wait for the initial analysis.
3. In the follow-up box, ask one or two questions (e.g. "What about TLD `example`?"). Confirm Claude responds.
4. Close the modal.
5. Navigate to **Data Exploration** (`/#/registry-dash/explore`).
6. Configure a query (Source: Domain Activity, Metric: Count, Group By: TLD) and click **Run Query**.
7. Verify the new **Add to AI Chat** button appears next to the run controls (icon: `auto_awesome`, label: "Add to AI Chat") and is **enabled**.
8. Click **Add to AI Chat** — menu shows two items: `add` **Start new chat** and `forum` **Add to current chat** (enabled).
9. Click **Add to current chat**.
10. The AI modal opens reflecting the prior conversation (the questions from step 3 and Claude's responses are visible) **plus** a new user turn that contains a one-line descriptor summary, the JSON descriptor, and the first 100 rows.
11. Wait for Claude's response. It should reference both the prior context (Domain Activity discussion) and the new Explore data.

### Steps — happy path "Start new chat":
1. From a fresh tab/session (no prior AI conversation), navigate to **Data Exploration**.
2. Run a query (any).
3. Click **Add to AI Chat** — menu shows **Start new chat** enabled and **Add to current chat** **disabled** (no conversation exists).
4. Click **Start new chat**.
5. The AI modal opens with no prior conversation; the seed user turn is the standard explore "Summarize trends" message and Claude's response references only the Explore data.
6. Close and reopen via the existing sparkle button on the Explore page — confirm it now resumes the conversation rather than starting a new one (the modal shows the prior turns).

### Edge cases:
- Before any query is run on the Explore page, the **Add to AI Chat** button is **disabled**.
- "Add to current chat" menu item is **disabled** when no AI conversation exists in the session (hard-refresh the page to clear in-memory state, then return to Explore — the menu item must be greyed out).
- Run an Explore query that returns more than 100 rows. Verify the "Add to current chat" user turn explicitly notes truncation (e.g. "100 of 4213 rows attached") and that `chartData.rows.length === 100` in the network request payload (DevTools → Network → analyze).
- Click "Start new chat" link in the modal header while an existing conversation is in progress — confirm the modal clears the prior history.

### Expected:
- Cross-page resume works: the prior conversation survives the modal close and the page navigation.
- Claude's response in the resumed chat references both the prior context and the new Explore data.
- No more than 100 rows are sent in `chartData`.
- The descriptor (`metadata.exploreDescriptor`) is present on the analyze request (DevTools → Network → request body) so a future backend follow-up can render it structurally.
- No backend errors; existing sparkle button on every page still opens fresh chats independently.
## Test 22: Tier 3 Batch 2 — get_registrar_details

Goal: confirm get_registrar_details fires when the user asks about a specific registrar. Steps: open modal on Overview, ask "Tell me more about registrar TheRegistrar". Watch for the "Looking up registrar" indicator. Server log should include toolsUsed=[get_registrar_details]. Final text should cite type/state/allowedTlds.

## Test 23: Tier 3 Batch 2 — get_tld_config

Goal: confirm get_tld_config fires for "how is TLD X configured" questions. Steps: open modal on Overview, ask "How is the example TLD configured? Which registrars can sell on it?". Watch for the "Looking up TLD config" indicator. Final text should cite TLD state, currency, and at least one allowed registrar by name. TLDs with > 100 allowed registrars should mention truncation.

## Test 24: Tier 3 Batch 2 — query_revenue_breakdown

Goal: confirm query_revenue_breakdown fires for revenue drill-down questions. Steps: open modal on Financials > Registry Revenue, ask "Break down revenue for tld example over the last 6 months by operation". Repeat with "by period". Verify date ranges over 2 years are rejected by the tool, and group_by=registrar returns a tool error (not supported in v2).

## Test 25: Tier 3 Batch 2 — query_renewal_rates

Goal: confirm query_renewal_rates fires for renewal-trend questions. Steps: open modal on Overview, ask "What's the renewal rate for tld example over the last year?". Final text should cite a numeric renewal rate.

## Test 26: Tier 3 Batch 2 — query_expiration_curve

Goal: confirm query_expiration_curve fires for forward-looking expiration questions. Steps: open modal on Financials > Forecasting, ask "How many domains in tld example expire in the next 12 months, broken out by month?". Verify months_ahead outside [1, 60] is silently clamped.
---

## Test 27: Tier 3 Tool Use - Generic run_explore_query (positive)

**Goal:** Confirm Claude reaches for `run_explore_query` for questions no specific tool answers.

### Steps:
1. Navigate to **Domain Activity** and open the analysis modal.
2. Wait for the initial analysis to finish.
3. Ask: `What is our average renewal price by registrar over the last quarter for tld example?` (substitute a TLD with seeded REVENUE data).
4. Watch for an inline indicator: `🔬 Running data query` (italic, slight pulse).
5. The final assistant text should cite per-registrar pricing/renewal data.
6. Tail the server log for `RegistryDashAiAction` and find the matching `AI analysis request:` line.

### Expected:
- Indicator `🔬 Running data query` appears mid-stream.
- Final answer contains per-registrar numbers, not just a generic summary.
- Server log includes `toolsUsed=[run_explore_query]`.
- A second log line from `RunExploreQueryTool` records the descriptor: `AI tool run_explore_query: user=... tld=example dataSource=REVENUE dimensions=[registrar] metrics=[amount] ...`.

---

## Test 28: Tier 3 Tool Use - Generic vs Specific Tool Selection (negative)

**Goal:** Confirm Claude prefers specific tools over the generic when one applies. Otherwise the description-level tie-breaker is too weak and needs strengthening.

### Steps:
1. Navigate to **Domain Activity**.
2. Open the analysis modal.
3. Ask: `Show me transfers for tld example last week.` - a specific tool (`query_transfers`) covers this.
4. Watch the indicator that appears.

### Expected:
- Indicator is `🔍 Searching transfers`, NOT `🔬 Running data query`.
- Server log shows `toolsUsed=[query_transfers]`, not `run_explore_query`.
- If `run_explore_query` fires here, the description's tie-breaker copy needs a stronger lead-in.

---

## Test 29: Tier 3 Tool Use - Statement-timeout error message

**Goal:** Confirm the `statement_timeout` config knob produces a user-visible error rather than a generic gateway timeout.

### Steps:
1. Temporarily set `ai.tools.statementTimeoutSeconds: 1` in the local-stack `default-config.yaml` override.
2. Restart the local stack.
3. Open the analysis modal and ask a wide aggregation question that will exceed 1s (e.g. `Aggregate all REVENUE for the last year by registrar and operation`).
4. Watch the modal response.

### Expected:
- Final text reads something like *"Query exceeded 1s - try a narrower date range or smaller scope."*
- Server log includes the descriptor line and the SQL state 57014.
- No generic 502/504 gateway error reaches the browser.

---

## Test 30: Tier 3 v1 — All four tools fire with correct indicator

**Goal:** Verify each of the four PR #122 tools (`query_transfers`, `get_pricing_rules`, `query_registrar_activity`, `query_domain_details`) is reachable from a natural-language prompt and that the SSE wire format includes `tool_use` / `tool_result` / `done` events for at least one of them.

### Prerequisites:
- Local-dev or alpha. Local-dev: run `helpers/seed-test-data.sh` so seeded transfer history, pricing rules, and at least one domain with multi-event history exist.

### Steps:
1. Navigate to **Domain Activity**, open the analysis modal via sparkle.
2. After the initial analysis completes, in the follow-up box ask: `What domains transferred on tld example in the last 30 days?` Watch for the `🔍 Searching transfers` indicator.
3. In the same modal, follow up: `What's the current pricing for tld example?` Watch for `💰 Looking up pricing`.
4. Follow up: `What activity did TheRegistrar do last month on tld example?` Watch for `📊 Checking registrar activity`.
5. Follow up: `Tell me everything about the domain <name from step 2>.` Watch for `🔎 Looking up domain`.
6. Open DevTools → Network → click the most recent `/console-api/registry-dash/ai/analyze` request → **EventStream** tab. (Do **not** install any in-page response interceptor that calls `response.clone()` — see SKILL.md note.)
7. Tail the test server log (or Cloud Logging on alpha) for `RegistryDashAiAction`.

### Expected:
- Each follow-up triggers its labelled indicator mid-stream and the indicator clears once Claude resumes text.
- The DevTools EventStream tab shows at least one frame of each: `{"type":"text",...}`, `{"type":"tool_use","tool":"query_transfers","args":{...}}`, `{"type":"tool_result","tool":"query_transfers","ok":true}`, `{"type":"done"}`.
- The four corresponding `AI analysis request:` server-log lines include `toolsUsed=[query_transfers]`, `toolsUsed=[get_pricing_rules]`, `toolsUsed=[query_registrar_activity]`, and `toolsUsed=[query_domain_details]` respectively.

---

## Test 31: Tier 3 Batch 2 — Guardrail validation

**Goal:** Verify the validation gates documented in PR #124 produce user-visible tool errors rather than silently broken responses: `query_revenue_breakdown` rejects ranges > 2y and `group_by=registrar`; `query_expiration_curve` clamps `months_ahead` to [1, 60]; `get_tld_config` truncates `allowed_registrars` at 100.

### Prerequisites:
- Local-dev preferred (server log inspection is required). Alpha works for the date-range and clamp checks but not for the truncation case unless a TLD with > 100 allowed registrars exists.
- For the truncation case, run `helpers/seed-test-data.sh` (or equivalent) to attach > 100 registrars to one TLD.

### Steps:
1. Open the analysis modal on **Financials → Registry Revenue**.
2. Ask: `Break down revenue for tld example from 2022-01-01 through 2025-01-01 by operation.` (3-year span — exceeds the 2-year cap.)
3. Watch the streamed response and tail the server log line for `toolsUsed=[query_revenue_breakdown]`.
4. Close the modal. Reopen on Registry Revenue. Ask: `Break down revenue for tld example over the last 6 months by registrar.`
5. Tail the server log again.
6. Close. Open the modal on **Financials → Forecasting**. Ask: `How many domains in tld example expire in the next 240 months, broken out by month?` Tail the log for the descriptor line.
7. Close. Open the modal on **Overview**. Ask: `How is the example TLD configured? List every registrar that can sell on it.` (Use the seeded TLD with > 100 allowed registrars.)

### Expected:
- Step 2: final assistant text includes a phrase like "date range exceeds the 2-year limit" (the tool returned an `is_error: true` result and Claude paraphrased it). Server log shows the `query_revenue_breakdown` invocation.
- Step 4: final assistant text says `group_by=registrar` is not supported (or paraphrase). Server log shows the same tool name but a tool-error result.
- Step 6: tool fires once, server log's per-tool descriptor (or the final assistant text) shows `months_ahead=60` — the value was silently clamped from 240 down to 60.
- Step 7: assistant text mentions truncation ("showing 100 of N allowed registrars" or similar). Inspecting the SSE `tool_result` event (DevTools → EventStream) is fine; the result body itself is not on the wire (`tool_result` carries only `ok`), so confirmation comes from the assistant's paraphrase.

---

## Test 32: Tier 3 Batch 2 — Registrar-scoped permission denial

**Goal:** Verify `get_registrar_details` enforces the new registrar-scoped permission gate (distinct from the TLD scope checked by Test 20).

### Prerequisites:
- A non-FTE user mapped to one specific registrar (e.g. `TheRegistrar`) and **not** mapped to another registrar that exists in the system (e.g. `NewRegistrar`). If the seeded fixture / `seed-test-data.sh` doesn't already provide one, mark this test partial and note the gap.

### Steps:
1. Log in as the scoped non-FTE user.
2. Navigate to **Overview**, open the analysis modal.
3. After the initial analysis, ask: `Tell me about registrar NewRegistrar.`
4. Watch for the `🏢 Looking up registrar` indicator.
5. Tail the server log for the `AI analysis request:` line.

### Expected:
- The indicator appears (the tool was called) and is replaced by a final assistant message of the form "I don't have access to that registrar" — never any details about NewRegistrar (no `iana_identifier`, no contacts, no allowed_tlds).
- Server log shows `toolsUsed=[get_registrar_details]` with no stack trace.
- Repeat the question with `TheRegistrar` (the user *is* mapped to it) — the assistant returns the registrar profile, confirming the denial in step 3 was scoped, not blanket.

---

## Test 33: Add to AI Chat — Conversation continues only via explicit "Add to current chat"

**Goal:** Verify post-#127 behavior: the conversation owned by `AiAnalysisService` is preserved *only* when the user explicitly opts in via the Explore split-button "Add to current chat" item. Sparkle clicks on any page reset the conversation before opening (the singleton service is no longer treated as a per-page session resumer).

### Prerequisites:
- Same as Test 21. Test data sufficient for both the Domain Activity sparkle prompt and a non-empty Explore query result.

### Steps:
1. Navigate to **Domain Activity**, open the AI modal via the sparkle button → "Summarize trends". Wait for the initial analysis.
2. Ask one follow-up: `Which TLD looks most active right now?` Wait for the response.
3. Close the modal.
4. Navigate to **Data Exploration**, configure any query (Source: Domain Activity, Metric: Count, Group By: TLD), click **Run Query**.
5. Click **Add to AI Chat** → **Add to current chat**.
6. Confirm the modal opens with the prior two turns still visible (the "Summarize trends" turn and the TLD follow-up), plus a new user turn carrying the descriptor and rows.
7. Wait for Claude's response, then close the modal.
8. Navigate to **Pricing**, click the **sparkle button**.
9. Confirm the modal opens with a **fresh** conversation — only the new "Summarize trends — Pricing" seed turn. The prior Domain Activity / Explore turns must NOT be present (sparkle calls `resetConversation()` before `dialog.open()` per PR #127).
10. From Explore again, click **Add to AI Chat** → **Start new chat**, then verify the chat opens with only the Explore-attached turn (no carry-over from the Pricing seed).

### Expected:
- Step 6: history is preserved across modal close + page navigation when re-entered through "Add to current chat".
- Step 9: sparkle on a different page produces a clean conversation. Any bleed-through of prior turns is a regression of PR #127.
- Step 10: "Start new chat" from Explore is also a clean reset.
- No `Response interrupted. Try again?` ever appears, even if step 7's stream is closed mid-flight (PR #127 suppresses the user-visible interruption error on aborted fetches; cross-check via the SKILL.md note that no interceptor is calling `response.clone()` on `/ai/analyze`).

---

## Test 34: run_explore_query — promptVersion bump to v1.0.2

**Goal:** Confirm the `ai.prompts.version` bump (v1.0.1 → v1.0.2) shipped in PR #126 reaches the orchestrator log line, since the bump is what carries the tools-header tie-breaker that drives Test 28's specific-vs-generic selection.

### Prerequisites:
- Local-dev or alpha with access to `RegistryDashAiAction` log lines.

### Steps:
1. Open the analysis modal on any page (Domain Activity is fine).
2. Trigger any analysis (initial sparkle prompt is enough — no follow-up needed).
3. Tail the server log for the `AI analysis request:` line.

### Expected:
- The log line includes `promptVersion=v1.0.2` (not `v1.0.1` or `v1`).
- If `promptVersion=v1.0.1` is observed, `default-config.yaml`'s `ai.prompts.version` was not updated and Test 28's tie-breaker copy is also likely stale — file as a regression.

---

## Test 35: run_explore_query — Row-cap truncation (local-dev only)

**Goal:** Verify the `ai.tools.maxRows` config knob caps the result payload and surfaces truncation either in the assistant's final text or in the audit log.

### Prerequisites:
- Local-dev only. Edit `default-config.yaml`'s `ai.tools.maxRows` to a small value (e.g. `5`). Restart the test server. Seed enough rows that an aggregation will exceed 5.

### Steps:
1. Open the analysis modal on **Domain Activity**.
2. Ask a question that forces a wide-but-cheap aggregation, e.g. `For tld example, give me the count of activity per registrar per month for the last 12 months.` (Should produce > 5 rows.)
3. Watch for the `🔬 Running data query` indicator.
4. Wait for the final assistant text.
5. Tail the server log for both the `RunExploreQueryTool` audit line and the `RegistryDashAiAction` `toolsUsed=` line.
6. Revert `ai.tools.maxRows` and restart.

### Expected:
- The audit line from `RunExploreQueryTool` records the descriptor (`dataSource=DOMAIN_ACTIVITY`, `dimensions=[registrar, period]` or similar) and includes `truncated=true` (model-independent — this is the primary pass criterion).
- The final assistant text either explicitly notes truncation ("showing 5 of N rows", or paraphrase) or is qualified ("partial results") — secondary criterion since it depends on Claude's paraphrasing.
- No 502/504 or "Response interrupted" error reaches the browser.

---

## Test 36: Modal state reset on open — no stale "Response interrupted" or cross-chart prompt bleed

**Goal:** Verify the PR #127 fixes for the AI chat modal: SSE fetches are wrapped in an `AbortController`, in-flight streams are cancelled on supersede / reset, the user-visible "Response interrupted. Try again?" is suppressed for aborted (non-network) fetches, and clicking the sparkle on a different chart does NOT fire chart-A's prompt while opening on chart B.

### Prerequisites:
- Local-dev or alpha. No special seed.

### Steps:
1. Navigate to **Portfolio**, open the AI modal via the sparkle button → wait for the initial analysis to **start streaming** (see chunks arriving in the modal).
2. While the stream is still in flight, close the modal (X / Esc).
3. Confirm the modal closes immediately and no `Response interrupted. Try again?` toast or modal-content message appears.
4. Open DevTools → Network → confirm the in-flight `/console-api/registry-dash/ai/analyze` request shows status `(canceled)` (or equivalent client-side abort).
5. Navigate to **Pricing**. Click the sparkle button.
6. Confirm the modal opens with the Pricing seed prompt — NOT the Portfolio seed, NOT a stale Portfolio response, no flash of the prior streaming text.
7. Repeat steps 1–4 but instead of closing in step 2, click the modal-header **"Start new chat"** while still streaming.
8. Confirm the prior stream is aborted, the modal clears, and the next user submission proceeds cleanly.
9. As a negative case, simulate a real network error (e.g. block the `/ai/analyze` endpoint via DevTools network throttling → Offline) and trigger a sparkle request.
10. Confirm the modal **does** show "Response interrupted. Try again?" for this case (the suppression is scoped to user-initiated aborts only).

### Expected:
- Steps 3, 6, 8: no false interruption text and no chart-A prompt bleed onto chart-B.
- Step 4: the request appears as cancelled in DevTools — not as failed-without-cancel.
- Step 10: genuine network failures still surface the user-visible error, confirming the suppression in PR #127 was abort-scoped, not blanket.
- Per the SKILL.md "Streaming endpoints" note, do NOT install any in-page response interceptor calling `response.clone()` while running this test — it would re-introduce false interruptions and confound the result.

---

## Test 37: AI SSE response — multibyte characters render correctly (UTF-8 charset)

**Goal:** Verify the PR #128 fix: the AI SSE response carries `Content-Type: text/event-stream; charset=utf-8` and the writer is UTF-8-encoded, so em-dashes, smart quotes, and emoji from Anthropic render as themselves in the modal — not as `?` or `??`.

### Prerequisites:
- Local-dev or alpha. The model needs to actually emit multibyte characters; provoking this reliably is easiest with an explicit prompt.

### Steps:
1. Open the analysis modal on **Domain Activity**.
2. After the initial analysis renders, ask the follow-up: `Reply with exactly this text and nothing else: "Active TLDs — top performers: 'example' and 'app' 🎯". Use the em-dash and curly quotes I sent.`
3. Watch the streamed response chunk-by-chunk in the modal.
4. Open DevTools → Network → click the request → **Headers** tab.
5. Tail the test server log for any character-encoding warnings (none expected).

### Expected:
- The modal text faithfully renders `—` (em-dash, U+2014), `'` and `'` (curly quotes, U+2018/2019), and the 🎯 emoji (U+1F3AF). No `?` substitutions, no mojibake, no double-glyphs.
- DevTools → Headers → Response Headers shows `content-type: text/event-stream;charset=utf-8` (charset present, lowercase or uppercase fine).
- Server log is clean — no `Unsupported encoding` or `MalformedInput` warnings.
- (Optional sanity) Repeat against an environment that does NOT have PR #128 — e.g. a stale alpha pre-deploy — and confirm the same prompt produces `?` substitutions there. This is the regression baseline.

---

## Test 38: Dynamic Model Catalog — chat modal reads from server

**Goal:** Verify the chat modal's model selector is driven by the live `AnthropicModelCatalog` served at `GET /console-api/registry-dash/ai/analyze`.

### Steps:
1. Open the analysis modal via any sparkle.
2. With DevTools - Network open, capture the request to `GET /console-api/registry-dash/ai/analyze` issued on modal open.
3. Response: `{ catalog: { opus: [...], sonnet: [...], haiku: [...] }, fetchedAt: "<ISO-8601>" }`. Each entry has `id` (e.g. `claude-sonnet-4-5-20250929`) and optional `displayName` / `createdAt`.
4. Verify the model toggle group renders a tab for every family that has at least one entry — and only those.
5. Click each visible tab; selection persists to localStorage + per-user settings.

### Edge cases:
- Family with no entries: tab in the chat modal must be hidden (not greyed out, not present).
- Stale saved selection: if the saved family is no longer in the catalog, modal falls back to the first available family.

### Expected:
- Tabs match the families present in the GET response.
- POST `/console-api/registry-dash/ai/analyze` still sends family shorthand (`haiku`/`sonnet`/`opus`) in `request.model` — server-side resolution unchanged.

---

## Test 39: Admin AI Models panel

**Goal:** Verify the admin page renders the live model catalog and the fetched-at timestamp.

### Prerequisites:
- Logged in as an FTE/admin user (admin GET requires `MANAGE_COST_BASIS`).

### Steps:
1. Navigate to **Admin** (`/#/registry-dash/admin`).
2. Verify a card titled **AI Models** appears near the top (above "My View").
3. Subtitle: "Top 3 GA models per family fetched from Anthropic. Auto-refreshed lazily on read; click below to force-refresh now." followed by `Fetched: <ISO-8601>`.
4. Body shows three columns labeled **Opus**, **Sonnet**, **Haiku**, each listing up to 3 entries. Each entry shows the model id in monospace; if `displayName` is present, it renders below in a smaller, muted style.
5. **Refresh now** button below the grid is enabled by default.

### Expected:
- Catalog matches `GET /console-api/registry-dash/admin` (`aiModelCatalog` + `aiModelCatalogFetchedAt`).
- Empty families render as italic "none".

---

## Test 40: Force-refresh AI model catalog

**Goal:** Verify the **Refresh now** button re-fetches `/v1/models` from Anthropic and updates `fetchedAt`.

### Steps:
1. On the **Admin** page, note the current `Fetched:` value.
2. Click **Refresh now**. Button label briefly changes to "Refreshing…" and is disabled in flight.
3. Network tab shows a POST to `/console-api/registry-dash/admin` with body `{"action":"refreshAiModels"}` returning 200 with the new catalog payload.
4. Within ~1-2 seconds the button returns to "Refresh now" and `Fetched:` advances.
5. Open the chat modal — selector reflects any net-new model in the appropriate family.

### Edge cases:
- Anthropic 5xx: catalog falls back to a hardcoded seed and the admin request still returns 200; `fetchedAt` advances. Server log: `Anthropic model catalog refresh failed; falling back to seed.`.

### Expected:
- `Fetched:` timestamp strictly increases across clicks.
- Other app instances/tabs pick up the change at their own next TTL expiry (not immediately) — cache is per-instance.

---

## Test 41: Complexity-based routing for background turns

**Goal:** Confirm `AiOrchestrator` runs turn 0 on the user-selected model and routes post-tool synthesis turns to a cheaper model based on the max complexity of tools just executed.

### Prerequisites:
- Local-dev or alpha (so server logs are accessible).
- `ai.complexityRoutingEnabled: true` (default).

### Steps:
1. On **Pricing**, open the AI modal.
2. Pick **Opus** in the model selector.
3. Ask: `What are our pricing rules for tld example?` — exercises `get_pricing_rules` (EASY).
4. Tail the server log for `AiOrchestrator` lines.
5. Repeat MEDIUM: on **Financials > Registry Revenue**, ask `Break down revenue for tld example over the last 6 months by operation` (`query_revenue_breakdown`, MEDIUM).
6. Repeat COMPLEX on **Domain Activity**: ask `What is our average renewal price by registrar over the last quarter for tld example?` (`run_explore_query`, COMPLEX).

### Expected (per server log):
- Two `AI turn=...` log lines from `AiOrchestrator` per request:
  - `turn=0` always logs `model=claude-opus-...` (user-selected) regardless of tool.
  - `turn=1` model depends on prior turn's max complexity:
    - EASY -> `model=claude-haiku-...`
    - MEDIUM -> `model=claude-sonnet-...`
    - COMPLEX -> `model=claude-opus-...` (no downgrade)
- Each turn includes `inputTokens=N, outputTokens=N`.
- The `RegistryDashAiAction` summary line logs `modelShorthand=opus` (user's selection), unchanged by routing.

### Rollback verification:
- Set `ai.complexityRoutingEnabled: false` in the local-stack config override and restart.
- Repeat step 3. Both turn 0 and turn 1 should log `model=claude-opus-...`. Set the flag back to `true` afterwards.

### Tools complexity reference (as of this PR):
- **EASY** — `get_pricing_rules`, `get_tld_config`, `get_registrar_details`.
- **MEDIUM** (default) — `query_transfers`, `query_registrar_activity`, `query_domain_details`, `query_revenue_breakdown`, `query_renewal_rates`, `query_expiration_curve`.
- **COMPLEX** — `run_explore_query`.
