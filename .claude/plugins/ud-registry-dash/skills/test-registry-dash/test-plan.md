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
