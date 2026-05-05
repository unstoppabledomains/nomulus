# Registry Dashboard AI Analysis — E2E Test Plan

## Prerequisites

- Access to the Nomulus console (either local dev server at `http://localhost:4200/console` or alpha at `https://console.dnex-alpha.com/console`).
- User must have dashboard access (`VIEW_DASHBOARD_OVERVIEW` permission).
- If running locally: test server running with `ANTHROPIC_API_KEY` env var set, Angular dev server on port 4200.
- Production is **never** a valid test environment.

> **Note on prompt menu icons:** Prompts use Material icons (`bar_chart`, `search`, `lightbulb`, `warning`) rendered alongside the labels. Earlier draft language referenced emoji glyphs (📊/🔍/💡/⚠️); the implementation uses the Material equivalents.

## Test 1: Sparkle Button Visibility

**Goal:** Verify sparkle buttons appear on every dashboard page and on every Financials sub-tab (per SRE-1957 universal coverage).
**Tier:** smoke

### Steps:
1. Navigate to **Domain Activity** (`/#/registry-dash/domain-activity`).
2. Verify two sparkle icons (`auto_awesome`) appear — one per chart ("Activity Breakdown by TLD" and "Current Domain Counts by TLD").
3. Hover over the sparkle icon — verify tooltip says "Analyze with AI".
4. Navigate to **Financials → Overview** tab. Verify a sparkle icon appears on the "Registry Revenue by Operation" chart (added in SRE-1957).
5. Switch to **Financials → Default Fees by TLD** tab. Verify a sparkle icon appears next to the "Default Fees per TLD per Operation" section heading (added in SRE-1957). This single sparkle covers both the bar chart and the fees table — its chart-context payload bundles both views.
6. Switch to **Financials → Effective Fees** tab. Verify a sparkle icon appears next to the "Effective Fees by Registrar" heading (added in SRE-1957).
7. Switch to **Financials → Registry Revenue** tab. Verify two sparkle icons appear on the revenue charts ("Registry Revenue by TLD", "Registry Revenue by Operation").
8. Switch to **Financials → Forecasting** tab. Verify a sparkle icon appears on "Net Growth Projection". A second chart, "Domain Expirations by TLD", is conditionally rendered based on backend data availability — see [SRE-1935](https://linear.app/unstoppable-domains/issue/SRE-1935/charts-not-displaying-review-expirationcurveoptions) for the open investigation. If the second chart renders, it should also have a sparkle icon.
9. Navigate to **Overview** (`/#/registry-dash/overview`).
10. Verify three sparkle icons appear, one per chart: "Registrar Market Share", "Domain Activity Trend", "Renewal Rate by TLD".
11. Navigate to **Data Exploration** (`/#/registry-dash/explore`).
12. Before running a query, verify exactly one `auto_awesome` icon is visible on the page — the page-level **Add to AI Chat** button next to the Run controls — and that button is **disabled**.
13. Configure a query (Source: Domain Activity, Metric: Count, Group By: TLD) and click "Run Query".
14. Verify a second `auto_awesome` icon appears above the rendered chart (per-chart sparkle), and the page-level **Add to AI Chat** button becomes enabled.
15. Navigate to **Portfolio** (`/#/registry-dash/portfolio`).
16. Verify a single sparkle icon appears in the page header row, to the right of the "Registrar Portfolio" heading.
17. Navigate to **Pricing** (`/#/registry-dash/pricing`).
18. Verify a single sparkle icon appears in the page header row, between the "Registrar Custom Pricing Rules" heading and the "Add Rule" button.

### Expected:
- Sparkle icons visible on every page and every Financials sub-tab. On Explore there are two sparkles: a page-level **Add to AI Chat** button (always present, disabled pre-query, enabled after first query) and a per-chart sparkle that appears above the chart once a query runs. No tab/chart that renders meaningful data is missing a sparkle.
- Icons are subtle (slightly transparent, opacity ~0.6) and become fully opaque on hover.
- Charts also have an "open in new" explore button alongside the sparkle on the pages that pre-date SRE-1957 (Portfolio + Pricing have only the sparkle, no explore button; the new Financials Overview/Default Fees/Effective Fees sparkles are sparkle-only).

---

## Test 2: Prompt Menu

**Goal:** Verify clicking the sparkle button shows the correct prompt menu for each page, including the SRE-1957 cold-start "Ask anything…" entry as the last item on every menu.
**Tier:** smoke

### Steps:
1. On **Domain Activity**, click the sparkle button.
2. Verify menu shows 4 options in this order (presets first, ask-anything last):
   - `bar_chart` Summarize trends
   - `search` Find anomalies
   - `lightbulb` Suggest actions
   - `chat` Ask anything…
3. Press Escape to close the menu.
4. Navigate to **Financials → Registry Revenue**, click sparkle. Verify the same 4 options as Domain Activity.
5. Navigate to **Financials → Forecasting**, click sparkle.
6. Verify menu shows:
   - `bar_chart` Summarize trends
   - `warning` Identify risks (NOT "Find anomalies")
   - `lightbulb` Suggest actions
   - `chat` Ask anything…
7. Navigate to **Overview**, click sparkle. Verify the same 4 generic options as Domain Activity.
8. Navigate to **Portfolio**, click sparkle. Verify the same 4 generic options.
9. Navigate to **Pricing**, click sparkle. Verify the same 4 generic options.
10. Navigate to **Financials → Overview**, click the SRE-1957 sparkle on the "Registry Revenue by Operation" chart. Verify the same 4 options as Registry Revenue (this sparkle reuses the `revenue-billing` page menu).
11. Navigate to **Financials → Default Fees by TLD**, click the SRE-1957 sparkle. Verify the same 4 options as Pricing (this sparkle reuses the `pricing` page menu).
12. Navigate to **Financials → Effective Fees**, click the SRE-1957 sparkle. Verify the same 4 options as Pricing.

### Expected:
- Every page's menu has exactly 4 prompt options — three page-specific presets plus "Ask anything…" pinned as the last entry.
- "Ask anything…" uses the `chat` Material icon and never appears in the middle of the list.
- Forecasting has "Identify risks" instead of "Find anomalies".
- Menu closes when clicking outside or pressing Escape.

---

## Test 3: Analysis Modal — Initial Request

**Goal:** Verify selecting a prompt opens the modal and streams an AI response.
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** smoke

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
**Tier:** full
**Tier rationale:** Requires editing config + restarting the test server and firing an analysis to validate.

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
**Tier:** smoke
**Tier rationale:** Cross-page nav DOM check — page-by-page sparkle visibility regression guard, no streaming required to confirm Portfolio + Pricing slot into nav.

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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

Goal: confirm get_registrar_details fires when the user asks about a specific registrar.
**Tier:** full
Steps: open modal on Overview, ask "Tell me more about registrar TheRegistrar". Watch for the "Looking up registrar" indicator. Server log should include toolsUsed=[get_registrar_details]. Final text should cite type/state/allowedTlds.

## Test 23: Tier 3 Batch 2 — get_tld_config

Goal: confirm get_tld_config fires for "how is TLD X configured" questions.
**Tier:** full
Steps: open modal on Overview, ask "How is the example TLD configured? Which registrars can sell on it?". Watch for the "Looking up TLD config" indicator. Final text should cite TLD state, currency, and at least one allowed registrar by name. TLDs with > 100 allowed registrars should mention truncation.

## Test 24: Tier 3 Batch 2 — query_revenue_breakdown

Goal: confirm query_revenue_breakdown fires for revenue drill-down questions.
**Tier:** full
Steps: open modal on Financials > Registry Revenue, ask "Break down revenue for tld example over the last 6 months by operation". Repeat with "by period". Verify date ranges over 2 years are rejected by the tool, and group_by=registrar returns a tool error (not supported in v2).

## Test 25: Tier 3 Batch 2 — query_renewal_rates

Goal: confirm query_renewal_rates fires for renewal-trend questions.
**Tier:** full
Steps: open modal on Overview, ask "What's the renewal rate for tld example over the last year?". Final text should cite a numeric renewal rate.

## Test 26: Tier 3 Batch 2 — query_expiration_curve

Goal: confirm query_expiration_curve fires for forward-looking expiration questions.
**Tier:** full
Steps: open modal on Financials > Forecasting, ask "How many domains in tld example expire in the next 12 months, broken out by month?". Verify months_ahead outside [1, 60] is silently clamped.
---

## Test 27: Tier 3 Tool Use - Generic run_explore_query (positive)

**Goal:** Confirm Claude reaches for `run_explore_query` for questions no specific tool answers.
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** full

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
**Tier:** smoke

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
**Tier:** smoke
**Requires:** admin-page

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
**Tier:** full
**Requires:** admin-page
**Tier rationale:** Triggers a server-side POST to refresh the catalog (admin-required state change, not pure UI render).

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
**Tier:** full

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
## Test 42: Admin Advanced panel visibility gating

**Goal:** Verify the Advanced (system prompt editor) panel is admin-only and resolves admin status from `UserDataService.userData()?.isAdmin` when the host doesn't pass `[isAdmin]` explicitly (PR #130).
**Tier:** smoke
**Requires:** admin-page

### Prerequisites:
- Two test users: one FTE/admin (`userData.isAdmin === true`) and one non-admin.
- Local-dev or alpha. Sparkle reachable on any page.

### Steps:
1. Log in as the **admin** user. Navigate to **Domain Activity**, click sparkle and choose "Summarize trends".
2. In the open modal, locate the "Advanced" toggle button (with `expand_more` chevron) directly under the model switcher.
3. Click the Advanced toggle. Verify a multiline `<textarea class="system-prompt-editor">` appears with placeholder `"System prompt (editable in dev mode; saved drafts persist across reloads for FTE)"`.
4. Click the toggle again. Verify the textarea collapses and the chevron flips between `expand_less` and `expand_more`.
5. Close the modal. Sign out and sign in as the **non-admin** user.
6. Navigate to **Domain Activity**, click sparkle and choose "Summarize trends".
7. Inspect the modal DOM (DevTools). Verify there is **no** `.advanced-section`, no Advanced toggle, no system-prompt textarea.

### Expected:
- Admin: Advanced toggle visible, textarea reveals on click, hides on second click.
- Non-admin: Advanced section absent from the DOM entirely (gated by `*ngIf="data.isAdmin"`).
- The sparkle button does not require an explicit `[isAdmin]` attribute on its host template; admin status falls through to `UserDataService` (verify by `grep` for `[isAdmin]` on the host page template — should be absent).

---

## Test 43: Admin system-prompt draft autosave + per-page scoping

**Goal:** Verify the Advanced textarea autosaves to localStorage under `ai-system-prompt-draft:<page>` per-keystroke, restores on reopen, and is scoped per-page so a draft on one page does not bleed into another (PR #130 review fix).
**Tier:** smoke
**Requires:** admin-page
**Tier rationale:** Pure localStorage + DOM state — opens modal, types, inspects keys, no streaming required.

### Prerequisites:
- Admin user. Local-dev or alpha.

### Steps:
1. On **Domain Activity**, open sparkle, choose "Summarize trends", expand Advanced.
2. Type a sentinel string into the textarea, e.g. `DRAFT-DOMAIN-ACTIVITY-12345`.
3. Open DevTools, Application, Local Storage. Verify a key `ai-system-prompt-draft:domain-activity` exists with the sentinel value (autosave is per-keystroke via `onSystemPromptChange`).
4. Close the modal **without** sending. Reopen sparkle, "Summarize trends" on the same page.
5. Verify the Advanced panel is **collapsed** by default (it must NOT auto-open even when a saved draft exists; this is the post-review "stale experiment silently runs" guard).
6. Click Advanced to expand. Verify the textarea is pre-filled with the sentinel value from step 2.
7. Close the modal. Navigate to **Pricing**. Open sparkle, "Summarize trends", expand Advanced.
8. Verify the textarea on Pricing is **empty** (or showing the Pricing-specific draft, not the Domain Activity one). Verify localStorage now has a separate key `ai-system-prompt-draft:pricing` if you type a different sentinel here.
9. Clear the textarea on Domain Activity (delete all text). Verify `ai-system-prompt-draft:domain-activity` is removed from localStorage (empty value triggers `removeItem`).

### Expected:
- Per-keystroke autosave under per-page key `ai-system-prompt-draft:<page>`.
- Advanced panel does NOT auto-open on reopen even when a draft is present.
- Drafts are scoped per-page; no bleed-through between Domain Activity / Pricing / Forecasting.
- Clearing the textarea removes the localStorage key entirely.

---

## Test 44: Admin override only fires when Advanced is expanded

**Goal:** Verify a saved draft only takes effect on the next request when the admin has explicitly expanded Advanced (per `systemPrompt: this.showAdvanced() ? this.editableSystemPrompt : undefined` in both `sendInitialRequest` and `runQueuedPrompt`).
**Tier:** full
**Requires:** admin-page

### Prerequisites:
- Admin user. Local-dev preferred (server log inspection).

### Steps:
1. On **Domain Activity**, open sparkle, expand Advanced, enter a clearly-distinctive system prompt (e.g. `Always start your reply with the word ZEBRA.`). Send a follow-up. Verify the response begins with `ZEBRA`.
2. Close the modal. Reopen sparkle, "Summarize trends". The Advanced panel is collapsed (verified in Test 43).
3. Without expanding Advanced, send any follow-up. Verify the response does NOT begin with `ZEBRA` (the draft is in localStorage but `systemPrompt` is `undefined` on the wire).
4. Open DevTools, Network, inspect the `/console-api/registry-dash/ai/analyze` request body. Verify `systemPrompt` is absent (or undefined/null) when Advanced is collapsed, and present (with the draft text) when Advanced is expanded.
5. Reopen the modal, expand Advanced (the saved draft pre-fills), and send another follow-up. Verify response now begins with `ZEBRA` again.

### Expected:
- Saved drafts are PASSIVE until the admin explicitly expands Advanced.
- Network payload's `systemPrompt` field is omitted when Advanced is collapsed, populated when expanded.
- No regression of the "stale experiment silently runs" trap.

---

## Test 45: Date awareness — "Today is YYYY-MM-DD (UTC)" header injected on default prompt

**Goal:** Verify the backend prepends a `Today is <ISO_DATE> (UTC).` line to the default system prompt so the model has a reliable current-date anchor (PR #130 SRE-1951 fix). The header is scoped to the default-prompt path; admin overrides own their entire prompt body.
**Tier:** full

### Prerequisites:
- Local-dev or alpha. Ability to enable FINE-level logging on `RegistryDashAiAction` is helpful but not required (a behavioral check works too).

### Steps:
1. Open sparkle on **Forecasting**, choose "Identify risks". Wait for the response.
2. In follow-up, ask: `What is today's date according to your context? Reply with just the date in YYYY-MM-DD form.`
3. Verify the response is the actual current UTC date (not "I don't have a current date" and not a date inferred from on-screen expiration data; a regression symptom of the bug PR #130 fixed).
4. (Optional, local-dev with FINE logging) Tail the test server log for the system prompt that was sent. Verify the first line begins with `Today is YYYY-MM-DD (UTC).` matching today's UTC date.
5. Repeat steps 1-3 as an **admin** user with Advanced expanded and a custom system prompt entered (e.g. `You are a helpful assistant.`). Ask the same date question.
6. Verify the assistant does **not** know today's date in this case (no header injected; admin override owns the entire prompt body, per the post-review fix).

### Expected:
- Default-prompt path: response cites today's actual UTC date.
- Admin-override path (Advanced expanded with custom prompt): assistant is unaware of today's date unless the admin's custom prompt mentions it. No double-stamping of the header.

---

## Test 46: dateRange — non-empty filter forwarded; empty/partial omitted; per-page opt-out

**Goal:** Verify the dateRange correctness fixes from PR #130: (a) sparkle button computes dateRange from the active filter via `computeDateRange(range.lookbackHours)`; (b) Pricing and Portfolio pages opt out via `[includeDateRange]="false"` so no fabricated 12-month range leaks; (c) backend `hasNonEmptyDateRange` requires both `start` AND `end` non-blank (whitespace-only also rejected); (d) Explore only emits dateRange for time-based data sources.
**Tier:** full

### Prerequisites:
- Local-dev or alpha. DevTools available.

### Steps:
1. **Filter-aware page:** Navigate to **Domain Activity**. Set the global time filter to "Last 30 days". Open sparkle, "Summarize trends".
2. In DevTools, Network, click the `/ai/analyze` request, Payload. Verify `metadata.dateRange.start` and `metadata.dateRange.end` are populated, non-empty ISO dates roughly 30 days apart and matching the filter.
3. **Filter-less page:** Navigate to **Pricing**. Open sparkle, "Summarize trends". Inspect the request payload. Verify `metadata.dateRange` is **absent** (key omitted entirely; Pricing opts out via `[includeDateRange]="false"`).
4. Repeat step 3 on **Portfolio**. Verify `metadata.dateRange` is also absent.
5. **Explore time vs non-time:** Navigate to **Data Exploration**. Run a query with Source = `Domain Activity` (time-based). Click sparkle, trigger an analyze. Verify `metadata.dateRange` is present in the payload along with `granularity`.
6. Run a query with Source = `Domain Counts` or `Pricing Rules` (non-time, static). Click sparkle. Verify `metadata.dateRange` is absent and `metadata.granularity` is also absent (only emitted for DOMAIN_ACTIVITY / REVENUE / RENEWAL_RATES / EXPIRATION_CURVE / TRANSACTIONS).
7. **Backend whitespace rejection (manual):** In DevTools console, fire a request with `metadata.dateRange = {start: "  ", end: "2026-01-01"}`. Confirm via server log (or via a query like "what date range did you receive?") that the LLM was not handed the partial range; it should be skipped server-side.

### Expected:
- Step 2: real, populated dateRange forwarded.
- Steps 3-4: dateRange key entirely absent from payload on Pricing/Portfolio.
- Steps 5-6: dateRange + granularity present only for time-based Explore data sources.
- Step 7: whitespace-only or partial ranges are dropped server-side; no `{start: "", end: "..."}` ever reaches the LLM.

---

## Test 47: Type during AI response — input remains enabled; queue hint shown

**Goal:** Verify SRE-1956 sub-feature 3: while a response is streaming, the follow-up textarea is editable, and a queue-hint line ("Will send after current response ...") appears whenever `streaming() && followUpText.trim().length > 0`.
**Tier:** full

### Prerequisites:
- Local-dev or alpha. Any page with sparkle.

### Steps:
1. Open sparkle on **Domain Activity**, "Summarize trends". As soon as the assistant text begins streaming, click into the follow-up textarea.
2. Type: `Tell me about TLD example growth`. Verify each keystroke registers (textarea is **not** disabled; this differs from pre-SRE-1956 behavior).
3. Verify the line `Will send after current response` appears below the input row while you type and the stream continues.
4. Press Enter (no Shift). Verify the textarea clears, a chip with truncated text "Tell me about TLD example growth" appears in the queue row above the input, and the queue hint updates to `Will send after current response (1 queued)`.
5. Type a second prompt while still streaming. Verify the hint updates to `(1 queued)` while typing (only existing chips count) and to `(2 queued)` after submitting the second.

### Expected:
- Textarea remains editable mid-stream.
- Queue hint visible only when streaming AND non-empty input.
- Queue hint count reflects current `pendingQueue.length` (chips already submitted).
- Submitting Enter while streaming pushes onto `pendingQueue` instead of firing immediately.

---

## Test 48: Prompt queue FIFO drain after current response

**Goal:** Verify queued prompts drain serially in FIFO order once the current response completes (auto-fire effect in `AiAnalysisModalComponent`).
**Tier:** full

### Prerequisites:
- Local-dev or alpha. A prompt that produces a slow / long response.

### Steps:
1. Open sparkle on **Domain Activity**, "Summarize trends" (long response).
2. While streaming, queue three prompts in order:
   - `A: how many TLDs are above 1k domains?`
   - `B: which registrar is top?`
   - `C: any anomalies?`
3. Verify three chips appear in order A, B, C with truncated previews if longer than 50 chars.
4. Wait for the current response to complete. Verify chip A disappears first; the assistant fires for prompt A; then chip B; then C, in order. Only one chip drains at a time (no parallel fire).
5. After all four turns complete (initial + A + B + C), scroll the conversation. Verify exactly 4 user turns and 4 assistant turns are present in `conversationHistory()`.

### Expected:
- FIFO order preserved across drain.
- Exactly one prompt fires at a time (the auto-fire effect re-runs only after `streaming()` flips back to false).
- Final history has all 4 user + 4 assistant turns; no missing or duplicated turns.
- No "drain entire queue at once" regression (the `firingInProgress` synchronous guard prevents this).

---

## Test 49: Edit queued chip — text returns to input, chip removed

**Goal:** Verify clicking on a queued chip body hoists its text back into the textarea and removes the chip (no duplicate after re-send).
**Tier:** full

### Steps:
1. Continue from Test 48 setup: queue 3 prompts mid-stream.
2. While the chips are still queued (current response still streaming), click on the BODY of chip B (not the cancel icon).
3. Verify chip B is removed from the queue (only A and C remain).
4. Verify the follow-up textarea now contains the full text of chip B.
5. Optionally edit the text and press Enter. Verify a new chip with the edited text appears at the END of the queue (after C; FIFO append), not in B's old position.

### Expected:
- Click on chip body sends text to input, chip removed.
- No duplicate chip if user re-submits.
- Re-submitted prompt is appended to the end of the queue.

---

## Test 50: Remove queued chip via cancel icon

**Goal:** Verify clicking the `cancel` icon on a queued chip removes only that chip without touching the input or the rest of the queue.
**Tier:** full

### Steps:
1. Queue 3 prompts (A, B, C) mid-stream as in Test 48.
2. Click the `cancel` (x) icon on chip B.
3. Verify chip B is removed; chips A and C remain in order.
4. Verify the follow-up textarea is unchanged (does NOT receive B's text).
5. Verify the queue label updates from `Queued (3):` to `Queued (2):`.

### Expected:
- Removal is surgical; only the targeted chip leaves the queue.
- Input not affected.
- Pending count signal updates immediately.

---

## Test 51: Stop pauses queue; Resume drains

**Goal:** Verify clicking Stop while streaming with queued prompts halts the stream cleanly, preserves the queue, surfaces a Resume button, and drains the queue when Resume is clicked.
**Tier:** full

### Steps:
1. Open sparkle, trigger a long response. While streaming, queue 2 prompts (A, B).
2. Click the **Stop** icon button (right side of the textarea; it shows the `stop` icon with tooltip "Stop response" while streaming, not the regular send arrow).
3. Verify the stream halts immediately. Partial assistant response disappears (no orphan turn). The 2 chips remain in the queue.
4. Verify a **Resume** button (`mat-stroked-button color="primary"`, label "Resume") appears next to the queue label.
5. Verify the queue does NOT auto-drain; the auto-fire effect short-circuits while `isPaused()` is true.
6. Click Resume. Verify chip A fires, then chip B, in order. Verify the Resume button disappears once the queue is empty (or stays only if `isPaused() && pendingCount() > 0`).

### Expected:
- Stop halts cleanly; queue chips preserved.
- Resume button only present while paused AND queue non-empty.
- Resume drains FIFO (same path as Test 48).

---

## Test 52: Mid-stream error preserves queue + Retry queue button

**Goal:** Verify when a network/server error interrupts a streaming response with prompts queued, the queue is preserved and a "Retry queue" button appears.
**Tier:** full

### Prerequisites:
- Local-dev preferred (kill the Anthropic backend or set a bogus admin system prompt to force a 502). Alpha works if you can inject an error reliably.

### Steps:
1. (Local-dev) As an admin, expand Advanced and paste an obviously broken system prompt that will 502 (or block the `/ai/analyze` endpoint via DevTools Network throttling, Offline, mid-stream). Trigger sparkle, response begins, fails.
2. While the failed response is still arriving (or just before), queue 2 prompts (A, B).
3. After the error surfaces (red error message in the conversation area), verify:
   - The queue chips for A and B are STILL visible.
   - A `Retry queue` button (`mat-stroked-button color="warn"`, scoped to `*ngIf="error() && pendingCount() > 0"`) appears.
4. Click **Retry queue**. Verify `error()` clears, `isPaused` clears, and the queue drains starting with A.

### Expected:
- Errors do not nuke the queue.
- Retry queue button only present when `error() && pendingCount() > 0`.
- Retry path clears both error and pause gates so the auto-fire effect can proceed.

---

## Test 53: Start new chat invalidates pending drain microtask

**Goal:** Verify the `drainGeneration` guard: clicking "Start new chat" while a queue head was just popped (auto-fire effect ran but its microtask hasn't fired `runQueuedPrompt` yet) does NOT cause the stale head to fire into the new chat.
**Tier:** full

### Steps:
1. Trigger sparkle on Domain Activity, wait for initial response.
2. Queue 2 prompts (A, B). Wait for current response to complete. Verify drain begins (chip A starts firing, becomes the new active stream).
3. While chip A is in flight (so chip B is the new head and the auto-fire effect has already popped it into a pending microtask), click **Start new chat** in the modal header.
4. Verify the conversation resets to the seed prompt; the queue clears (`pendingQueue` becomes empty); chip B does **not** fire into the new chat (would corrupt the fresh conversation).
5. Verify only the new initial-request stream is running; no stale head appears in the conversation.

### Expected:
- Start new chat resets queue, clears pause, bumps `drainGeneration`, and resets conversation.
- Any in-flight microtask captured the old `drainGeneration` and becomes a no-op (`gen !== this.drainGeneration`).
- No stale prompt corrupts the fresh chat.

---

## Test 54: Auto-scroll follows streaming output

**Goal:** Verify SRE-1956 sub-feature 1: when a long response streams, the conversation viewport auto-pins to the bottom as tokens arrive (per the modal's reactive `effect` watching `streamedText` + `conversationHistory`).
**Tier:** full

### Steps:
1. Open sparkle on **Domain Activity**, choose a prompt that produces a long, multi-line response (e.g. "Summarize trends" with Opus selected).
2. As tokens arrive, do not scroll. Verify the viewport stays pinned to the bottom; newest content always visible without manual intervention.
3. After the response completes, verify the viewport is still scrolled to the bottom (no jump-back).

### Expected:
- Viewport remains pinned to bottom for the full duration of the stream.
- `autoScrollEnabled` signal stays true; no Jump-to-latest FAB visible (next test verifies the FAB).
- No jitter or visible double-scroll on each chunk arrival (the rAF in `scrollToBottom` debounces this).

---

## Test 55: User scroll-up pauses auto-scroll; Jump-to-latest FAB appears + works

**Goal:** Verify scrolling up mid-stream stops auto-scroll, surfaces the floating "Jump to latest" FAB, and clicking the FAB re-pins to bottom.
**Tier:** full

### Steps:
1. Trigger a long streaming response as in Test 54.
2. Mid-stream, scroll UP within the conversation viewport (mouse wheel or trackpad).
3. Verify auto-scroll stops; the viewport stays where you scrolled to, even as new tokens arrive at the bottom.
4. Verify a `mat-mini-fab` button appears with icon `arrow_downward`, `aria-label="Jump to latest"`, `matTooltip="Jump to latest"`, anchored in the bottom-right corner of the conversation wrapper. The FAB shows ONLY while `!autoScrollEnabled() && streaming()`.
5. Click the FAB. Verify the viewport jumps to the bottom and `autoScrollEnabled` flips back to true (FAB disappears).
6. Verify auto-scroll resumes for subsequent tokens.

### Expected:
- `onConversationScroll` detects scroll-up (>40px from bottom) and flips `autoScrollEnabled` to false.
- FAB visible only mid-stream when not at bottom.
- FAB click calls `jumpToLatest()` which sets `autoScrollEnabled=true` and scrolls to bottom.
- The `programmaticScrollGuard` prevents the auto-scroll itself from being mistaken for user-scroll.

---

## Test 56: Scroll-up after stream completes — FAB hidden

**Goal:** Verify the FAB is gated by `streaming()`. Once the response is complete, scrolling up does NOT show the FAB (the user is in normal review mode).
**Tier:** full

### Steps:
1. Trigger a long streaming response, let it complete.
2. Scroll up within the conversation.
3. Verify NO Jump-to-latest FAB appears (since `streaming()` is false, `showJumpToLatest` is false).

### Expected:
- FAB only appears mid-stream; not after stream completion.
- User can freely scroll old turns without UI clutter.

---

## Test 57: Resize via drag handle — live update + persistence

**Goal:** Verify SRE-1956 sub-feature 4: dragging the bottom-right handle resizes the modal in real time, the chosen size persists across modal reopens via localStorage, and both entry points (sparkle + Explore "Add to Chat") honor the saved size.
**Tier:** smoke
**Tier rationale:** Modal chrome only — drag handle + localStorage write; the streaming response itself is not under test.

### Steps:
1. Clear `ai-modal-width-px` and `ai-modal-height-px` from localStorage.
2. Open sparkle on **Domain Activity**, "Summarize trends". Verify the modal opens at default 960px x 85vh.
3. Locate the resize handle: 14x14 striped element (`.ai-modal-resize-handle`) in the bottom-right corner of the modal surface.
4. Mousedown on the handle, drag the cursor down-right. Verify the modal frame resizes LIVE (per `dialogRef.updateSize` on each `sizeChange` emission). Verify the body cursor switches to `nwse-resize` during the drag.
5. Release. Verify body cursor returns to default.
6. Open DevTools, Application, Local Storage. Verify `ai-modal-width-px` and `ai-modal-height-px` now hold the new pixel values (rounded integers).
7. Close the modal. Reopen via sparkle. Verify the modal opens at the saved size.
8. Close. Open via the Explore page's **Add to AI Chat** menu (run a query first). Verify it also opens at the saved size (single source of truth via `aiModalConfig`).

### Expected:
- Live resize during drag (no preview rectangle; the actual modal resizes).
- localStorage updated only on `mouseup` (`onModalResizeCommit`), not on every move.
- Both entry points use `aiModalConfig(data)` and honor the saved size.

---

## Test 58: Resize clamping — max 95vw/95vh, min 480x400

**Goal:** Verify the directive clamps width/height to `[480, 0.95 * window.innerWidth]` and `[400, 0.95 * window.innerHeight]` per `AiModalResizeDirective.MIN_WIDTH/MIN_HEIGHT` and the `* 0.95` ceiling in `onMove`.
**Tier:** smoke

### Steps:
1. Open the modal. Drag the handle as far down-right as possible (off-screen).
2. Verify the modal stops growing at roughly 95% of the viewport width and height. It does not exceed either ceiling.
3. With the modal still open, try to shrink it by dragging the handle as far up-left as possible.
4. Verify the modal stops at width=480 and height=400 (cannot go smaller).
5. Verify localStorage values written on commit also fall within the clamped range.

### Expected:
- Maxima: ~95vw, ~95vh.
- Minima: 480x400.
- No way to push the modal off-screen or below a usable size.

---

## Test 59: Resize abort on alt-tab — body cursor restored, no commit

**Goal:** Verify the `window.blur` ABORT path: if the user mousedowns the handle and alt-tabs away before mouseup, the body cursor is restored, listeners are detached, and NO `sizeCommit` fires (saved size unchanged).
**Tier:** smoke

### Steps:
1. Open the modal. Note the current saved size in localStorage (e.g. 960, 800).
2. Mousedown on the resize handle. Begin dragging slightly so a new size is briefly applied (e.g. 1100, 900).
3. WITHOUT releasing the mouse, alt-tab to a different application window.
4. Alt-tab back to the browser.
5. Click anywhere outside the handle.
6. Verify body cursor is normal (not stuck on `nwse-resize`).
7. Verify no phantom drag is active (moving the mouse over the modal does NOT continue resizing).
8. Verify localStorage `ai-modal-width-px` / `ai-modal-height-px` are UNCHANGED from step 1 (the abort path does not commit).

### Expected:
- Body cursor and userSelect restored on `window.blur`.
- Mousemove and mouseup listeners detached.
- No `sizeCommit` event fired, so no localStorage write.
- Reopening the modal restores the pre-abort saved size (not the partial drag size).

---

## Test 60: Default size on first open (localStorage cleared)

**Goal:** Verify `aiModalConfig()` falls back to defaults (`width: '960px'`, `height: '85vh'`) when localStorage values are missing, non-finite, or below the minimums.
**Tier:** smoke

### Steps:
1. In DevTools, remove `ai-modal-width-px` and `ai-modal-height-px` from localStorage.
2. Open the modal via sparkle. Measure the rendered modal: should be ~960px wide x 85vh tall (matches `width: '960px'`, `height: '85vh'`).
3. Set `ai-modal-width-px` to `'Infinity'` or `'1e500'` in localStorage. Reopen the modal.
4. Verify it falls back to defaults (the `parseSavedDim` Number.isFinite guard).
5. Set `ai-modal-width-px` to `'200'` (below MIN_W=480). Reopen.
6. Verify it falls back to default 960 (clamp guard rejects sub-minimum).
7. Set both to valid values (e.g. `'1100'` and `'700'`). Reopen. Verify modal honors them.

### Expected:
- Missing values return defaults.
- Non-finite values return defaults.
- Below-minimum values return defaults (no sub-minimum modal).
- Valid values are honored exactly (with `px` suffix).

---

## Test 61: Theme switch — resize handle stripes still visible

**Goal:** Verify the resize handle stripes use a theme-aware color so they remain visible against both light and dark dialog surfaces (post-review fix in SRE-1956 ph3).
**Tier:** smoke

### Steps:
1. Open the modal in light mode. Locate the 14x14 striped handle in the bottom-right corner. Verify the stripes are clearly visible against the light surface.
2. Switch the app to dark mode (whatever toggle the console exposes; typically a settings menu).
3. Reopen the modal. Verify the stripes are still clearly visible against the dark surface (not invisible-on-dark, not low-contrast).
4. Drag the handle in dark mode and verify resize works identically.

### Expected:
- Handle remains visually discoverable in both themes.
- No regression to a hard-coded light-only color.

---

## Test 62: Textarea Shift+Enter newline + autogrow + send/stop swap + cancel preserves history

**Goal:** Verify SRE-1956 sub-features 2 (textarea + autosize) and 5 (Stop button + cancel); the multi-feature smoke for the input area: Shift+Enter inserts newline, Enter alone submits, autogrow caps at 6 rows then internally scrolls, long URLs wrap via overflow-wrap, the right icon swaps between `send` and `stop` based on `streaming()`, and clicking Stop mid-stream preserves the user turn but clears the partial assistant response.
**Tier:** full
**Tier rationale:** Send/stop swap and cancel-preserves-history both require an in-flight stream to validate.

### Steps — Shift+Enter newline + Enter submit:
1. Open the modal. Click into the follow-up textarea.
2. Type `line one`, press **Shift+Enter** (do not release Shift before Enter).
3. Verify a newline is inserted; the textarea now shows two lines and autogrows to ~2 rows.
4. Type `line two`. Press **Enter** (no Shift).
5. Verify the prompt submits (a 2-line user turn appears in the conversation; textarea clears).

### Steps — autogrow + autowrap:
6. Paste an 8-line block (e.g. a code snippet) via Shift+Enter or paste-with-newlines. Verify the textarea grows to ~6 rows (`cdkAutosizeMaxRows="6"`) then internally scrolls; does NOT push the entire modal apart.
7. Paste a single 200-character URL with no spaces. Verify the URL wraps within the textarea (via `overflow-wrap: anywhere`) and does not overflow horizontally.

### Steps — send/stop icon swap:
8. With the textarea empty and no stream active, verify the right-side `mat-icon-button` shows the `send` icon, tooltip = `Send`, and is **disabled**.
9. Type any text. Verify the button is now **enabled**, still showing `send`.
10. Submit. Verify the icon changes to `stop`, tooltip = `Stop response`, and the button is **enabled even when the textarea is empty** (the `[disabled]="!streaming() && !followUpText.trim()"` evaluates false because `streaming()` is true).
11. Wait for the stream to complete. Verify the icon reverts to `send`, tooltip back to `Send`, disabled when input is empty.

### Steps — cancel preserves history:
12. Submit a follow-up. Mid-stream, click the Stop icon button (or `onStop()` via Esc shortcut if exposed).
13. Verify the partial assistant response (the streaming text + tool indicators + progress bar) disappears. No orphan assistant turn is appended to history.
14. The user turn submitted in step 12 remains visible in the conversation.
15. Verify `conversationHistory().length` equals (pre-send count) + 1 (just the user turn; no assistant turn for the cancelled stream).
16. Submit a new follow-up. Verify the new request fires cleanly, references the prior context (since the user turn was preserved), and appends a fresh assistant turn on completion.

### Expected:
- Shift+Enter inserts a literal newline; Enter alone submits.
- Textarea grows up to 6 rows then scrolls internally; long unbroken strings wrap.
- Icon swap mirrors `streaming()` exactly: `send` <-> `stop`.
- Stop tooltip says `Stop response`; Send tooltip says `Send`.
- Stop button is enabled even with empty input while streaming.
- After Stop: user turn preserved in history; partial assistant response NOT appended; subsequent prompts continue the conversation cleanly.

---

## Test 63: Ask-anything cold-start entry (SRE-1957)

**Goal:** Verify the new "Ask anything…" entry opens an idle modal with no auto-fired request, and that submitting the first turn from the follow-up input fires `/console-api/registry-dash/ai/analyze` with `promptType: "ask_anything"`, the same chart-context payload a preset would have sent, and a single-message `conversationHistory` containing the user's typed text.
**Tier:** full

### Prerequisites:
- Local-dev or alpha. No special seed.

### Steps:
1. Open DevTools → Network and filter on `analyze`.
2. Navigate to **Domain Activity**.
3. Click the sparkle button on the first chart. The menu opens with four entries; the last entry is `chat` Ask anything…
4. Click "Ask anything…". The modal opens.
5. Confirm the modal is **idle** — no streaming progress bar, no assistant-bubble being built, no `Response interrupted` error. The conversation area is empty. The follow-up input at the bottom is enabled with placeholder "Ask anything about this chart…" (note: NOT "Ask a follow-up question…").
6. Confirm DevTools → Network shows **no** POST request to `/console-api/registry-dash/ai/analyze` was fired on modal open. (A `GET /console-api/registry-dash/ai/analyze` for the model catalog — Test 38 — is expected and not a regression.)
7. Type `What changed last week?` into the follow-up input and press Enter (or click the send button).
8. Confirm a single POST to `/console-api/registry-dash/ai/analyze` fires.
9. Inspect the request body:
   - `promptType` is `ask_anything`.
   - `metadata.filteredTlds`, `metadata.filteredRegistrars`, `metadata.dateRange` reflect the current dashboard filters (same shape as a preset request).
   - `chartData` is non-empty and matches the chart payload that a preset click on the same chart would send.
   - `conversationHistory` is exactly `[{role: "user", content: "What changed last week?"}]` — no seeded preset prompt prepended.
10. The assistant streams a response. The modal renders the streaming text and tool indicators normally.
11. Type a follow-up turn and submit. Confirm `conversationHistory` now contains user→assistant→user, exactly as on the preset path.
12. Click **Start new chat** in the modal header. Confirm the modal returns to the idle cold-start state — no auto-fired request, follow-up input ready to type — because the original entry was cold-start (empty seed).

### Expected:
- Step 6: zero POST `analyze` requests on modal open for the cold-start path. Preset path (regression check) still fires immediately on open.
- Step 9: payload shape and chart-context fields are identical to a preset request — only `promptType` and the seed user turn differ.
- Step 12: Start new chat does not silently send an empty-prompt request for cold-start entries.

---

## Test 64: Universal sparkle coverage on Financials sub-tabs (SRE-1957 drift guard)

**Goal:** Lock in SRE-1957's universal-coverage promise so future refactors don't silently strip the new ✨ buttons. Each Financials sub-tab is asserted independently with the page-type and prompt menu it should report at the analyze endpoint.
**Tier:** full
**Tier rationale:** Each sparkle click in the steps fires an `/ai/analyze` POST and inspects payloads from a real streamed turn.

### Prerequisites:
- Local-dev or alpha. Default `Fixture.java` data is sufficient.

### Steps:
1. Open DevTools → Network and filter on `analyze`.
2. Navigate to **Financials → Overview**. Click the sparkle on "Registry Revenue by Operation" → click "Summarize trends".
3. Inspect the `analyze` request: `page` is `revenue-billing`, `promptType` is `summarize_trends`. (The Overview chart reuses the revenue-billing prompt menu rather than introducing a new page type.)
4. Close the modal.
5. Switch to **Financials → Default Fees by TLD**. Click the sparkle next to the section heading → click "Summarize trends".
6. Inspect the request: `page` is `pricing`, `promptType` is `summarize_trends`. The `chartData` payload contains BOTH a `feesByTld` aggregated bar dataset AND a `tldFeeEntries` raw list (single sparkle covers both views).
7. Close the modal.
8. Switch to **Financials → Effective Fees**. Click the sparkle next to "Effective Fees by Registrar" → click "Summarize trends".
9. Inspect the request: `page` is `pricing`, `promptType` is `summarize_trends`. `chartData` is the filtered effective-fees row list.
10. For each of the three sparkles above, also click "Ask anything…" and confirm it opens the cold-start modal (no auto-fired POST `analyze` request) — these sparkles use the same shared component so the behavior must be uniform.

### Expected:
- All three SRE-1957 sparkles fire requests with the page types declared above.
- Tabs that previously had no sparkle now do; no Financials sub-tab is missing one when its data renders.
- The cold-start "Ask anything…" entry is uniformly available on every new sparkle.

---

## Test 65: Tier 3 Tool Use - Result-status chips (SRE-1958)

**Goal:** Verify the modal renders disambiguated status chips next to tool-call indicators when a tool returns a non-OK status, with the diagnostic visible on hover.

### Steps:
1. Navigate to **Domain Activity** and open the analysis modal (any prompt).
2. Wait for the initial analysis to finish.
3. Trigger an `EMPTY_FOR_RANGE` case: ask "Show transfers for tld example between 2025-01-01 and 2025-01-02." (adjust dates to a known-empty window in the target env).
4. Watch the tool indicator - when it resolves, a yellow/muted chip should appear with text "No data". Hover the chip; the tooltip must contain a diagnostic naming the active filter and data extent.
5. Trigger an `INVALID_ARGS` case: via DevTools issue an analyze request with `tld=""` or omit a required arg. The chip should be red, text "Invalid args", tooltip naming the offending arg.
6. Trigger a `PERMISSION_DENIED` case: while logged in as a non-FTE user with limited scope, ask about a TLD outside the scope. The chip should be red, text "No access".

### Expected:
- For `OK`: no chip, current silent green-checkmark behavior preserved.
- For `EMPTY_FOR_RANGE`: yellow/muted chip "No data" + diagnostic in `matTooltip`.
- For `OUT_OF_RANGE`: yellow chip "Out of range" + diagnostic.
- For `INVALID_ARGS`: red chip "Invalid args" + diagnostic.
- For `PERMISSION_DENIED`: red chip "No access" + diagnostic.
- For `INTERNAL_ERROR`: red chip "Tool error" + sanitized diagnostic.
- The streamed `tool_result` frame in the network tab carries `{type: "tool_result", tool, status, diagnostic?, ok}`.

---

## Test 66: Tier 3 - query_registrar_activity registrarIds filter (SRE-1958 regression)

**Goal:** Confirm the previously-broken `registrarIds` filter on `query_registrar_activity` now actually filters in SQL and returns rows.

### Steps:
1. Pick a TLD with seeded activity for at least two registrars (alpha: `food` + `acme`; local: default Fixture TLD with `TheRegistrar`/`NewRegistrar`).
2. Open the analysis modal on **Domain Activity**.
3. Ask: "What activity is there for tld <X> for registrar <Y> over the last 30 days?" with concrete names.
4. Watch the indicator and final response.
5. Tail the server log (or Cloud Logging on alpha-gke) for the `query_registrar_activity` SQL.

### Expected:
- Final text cites only domains/events for registrar `<Y>` - no rows from any other registrar.
- Emitted SQL contains a WHERE clause with `registrar_id IN (:registrarIds)` (or the equivalent parameter binding).
- Status chip is `OK` (no chip rendered) with non-empty data.
- If the same query is re-run with a registrar id outside the user's scope, response is `PERMISSION_DENIED`.

---

## Test 67: Tier 3 - March 2027 doesn't loop (SRE-1958 acceptance)

**Goal:** Confirm asking about a date range entirely beyond available data terminates in a single tool round-trip with `OUT_OF_RANGE`, instead of looping.

### Steps:
1. Open the analysis modal on **Domain Activity** (or **Financials > Registry Revenue** / **Forecasting**).
2. Ask: "Were there any transfers in March 2027?"
3. Watch the tool indicators and the final assistant text.
4. Tail the server log for `RegistryDashAiAction`.

### Expected:
- Exactly one `query_transfers` tool call fires (no retry loop).
- The `tool_result` frame carries `status: "OUT_OF_RANGE"` and a diagnostic naming the latest available data point.
- Modal renders a yellow "Out of range" chip with the diagnostic in tooltip.
- Final assistant text tells the user the range is in the future and suggests a different range - does NOT silently say "no transfers in March 2027" without context.
- Server log shows `toolsUsed=[query_transfers]` with no second invocation of the same tool with the same args.

---

## Test 68: Tier 3 — query_domain_footprint "largest registrar" (SRE-1962 acceptance)

**Goal:** Confirm the SRE-1962 acceptance criterion: "who is the largest registrar by registered domain footprint?" returns a real answer in one tool call via the new `query_domain_footprint` tool, not the old generic-blocked path.
**Tier:** full

### Prerequisites:
- Local-dev or alpha. At least two TLDs and two registrars with seeded `Domain` rows (current sponsorship), so the count breakdown is non-trivial.

### Steps:
1. Open the analysis modal on **Overview** (or any page).
2. Ask: `Who is the largest registrar across all of our TLDs?`
3. Watch for the inline indicator `🌐 Counting registered domains`.
4. Read the final assistant text.
5. Tail the server log for `RegistryDashAiAction` and the `query_domain_footprint:` descriptor line.

### Expected:
- Indicator `🌐 Counting registered domains` (NOT `🔬 Running data query`) appears mid-stream.
- Single tool call: `toolsUsed=[query_domain_footprint]`. No `run_explore_query` retry.
- Final assistant text names a single registrar and a count (e.g. "registrar X with N domains").
- Server log line includes `tlds=[]`, `registrarIds=[]`, `groupBy=registrar` (or `both` if the LLM picked the default), and `limit=100` (or whatever default is applied).
- Tool payload includes a `totalDomains` rollup field.

---

## Test 69: Tier 3 — query_domain_footprint by TLD and registrar

**Goal:** Confirm the breakdown variant works and that filtering + grouping return the expected shape.
**Tier:** full

### Steps:
1. Open the modal on Overview.
2. Ask: `Show me a breakdown of registered domains by registrar and TLD.`
3. Watch indicator and DevTools → Network → most recent `analyze` request → EventStream tab.
4. Confirm the `tool_result` frame for `query_domain_footprint`.

### Expected:
- Indicator `🌐 Counting registered domains`.
- `tool_result` data has `rows` array with `tld`, `registrar`, and `count` fields per row, sorted descending by `count`.
- `rowCount`, `totalDomains`, and `truncated` fields are present on the payload.
- Final assistant text presents a table-like or itemized breakdown.

---

## Test 70: Tier 3 — run_explore_query with DOMAIN_COUNTS without dates (SRE-1962 regression)

**Goal:** Confirm the SRE-1962 fix: `run_explore_query` with `DOMAIN_COUNTS` and no date params succeeds, instead of failing with `INVALID_ARGS` for missing dates (the original symptom).
**Tier:** full

### Prerequisites:
- Local-dev preferred (server log inspection required to confirm the descriptor line).

### Steps:
1. Open the modal. Ask a question subtle enough that Claude reaches for the generic tool against DOMAIN_COUNTS rather than `query_domain_footprint` (e.g. ask for an unusual dimension combo only the generic supports). If the LLM keeps picking `query_domain_footprint`, force the path by injecting via DevTools or direct API call:
   ```
   curl -s -X POST .../console-api/registry-dash/ai/analyze \
     -H 'Content-Type: application/json' \
     --data '{"data_source":"DOMAIN_COUNTS","tld":"example","metrics":["count"],"dimensions":["registrar"]}'
   ```
   (Adapt to whatever the actual tool-call path is.)
2. Tail the server log for `AI tool run_explore_query:` and the corresponding `tool_result` `status`.

### Expected:
- Tool returns `status: "OK"` (or `"EMPTY_FOR_RANGE"` if no data for the TLD), NOT `INVALID_ARGS`.
- Server log line shows `startDate=null endDate=null`.
- The descriptor line does not include the "Filter 'dateRange' not supported" message.

---

## Test 71: Tier 3 — run_explore_query DOMAIN_COUNTS with dates is silently stripped + noted

**Goal:** Confirm that when the LLM (or a manual caller) passes start_date/end_date with DOMAIN_COUNTS, the tool silently strips the dates, runs the query anyway, and surfaces a `notes[]` advisory in the OK payload — instead of returning INVALID_ARGS and blocking.
**Tier:** full

### Prerequisites:
- Local-dev preferred.

### Steps:
1. Force a `run_explore_query` call with `data_source=DOMAIN_COUNTS`, dimensions `[tld, registrar]`, metrics `[count]`, AND `start_date`/`end_date` populated. The simplest path: temporarily prompt-inject in the chat ("Run explore query against DOMAIN_COUNTS for tld example with start_date 2026-01-01, end_date 2026-04-30, dimensions [tld, registrar], metric count") or call the API directly.
2. Tail the server log for the descriptor line.
3. Inspect the `tool_result` frame in DevTools EventStream.

### Expected:
- Tool returns `status: "OK"` (or `"EMPTY_FOR_RANGE"`), NOT `INVALID_ARGS`.
- Server log shows `startDate=null endDate=null` (the dates were stripped before the descriptor was built).
- Tool payload includes a top-level `notes` array containing a string like: *"start_date/end_date were ignored: DOMAIN_COUNTS is a current-state snapshot and does not support date filtering."*
- Assistant final text either echoes the advisory ("note: dates aren't applicable here…") or just answers the question; it does NOT report a tool failure.

---

## Test 72: Tier 3 — Tool descriptions document per-source date requirement

**Goal:** Lightweight smoke that the AI tool registry surfaces the new per-source `dates=required|n/a` documentation. Protects against accidental regressions of the SRE-1962 description string.
**Tier:** smoke

### Steps:
1. Hit the local AI tool catalog endpoint (or the orchestrator log line that emits the Anthropic tool definitions).
2. Find the `run_explore_query` definition.

### Expected:
- The `description` text contains both `dates=required` (for sources like REVENUE, DOMAIN_ACTIVITY, RENEWAL_RATES, EXPIRATION_CURVE, TRANSACTIONS) and `dates=n/a` (for DOMAIN_COUNTS and PRICING_RULES).
- The `input_schema.required` array does NOT contain `start_date` or `end_date`.
