# Registry Dashboard AI — "Add to AI Chat" from Explore — Implementation Plan

**Spec:** [`2026-04-30-registry-dash-ai-explore-add-to-chat.md`](../specs/2026-04-30-registry-dash-ai-explore-add-to-chat.md)
**Linear:** SRE-1942

## Step-by-step (TDD-flavoured; compile-clean each step)

### 1. Models additions

- `ai-analysis.models.ts`: add JSDoc above `metadata` documenting well-known keys including `exploreDescriptor`. Export `EXPLORE_AI_ROW_CAP = 100`.
- No new tests needed (type-only changes).

### 2. Service refactor

- Add `conversationHistory`, `lastRequest`, `hasActiveConversation`, `resetConversation()` to `AiAnalysisService`.
- Modify `analyze` to:
  - capture `request.conversationHistory` into the service signal at the start, so the user turn renders immediately;
  - on success, append `{role: 'assistant', content: streamedText}` to history and capture `lastRequest`;
  - on error, leave history at the captured user-turn state (no assistant append).
- Add `appendUserTurnAndAnalyze(content, overrides)` that merges current history + `lastRequest` defaults + overrides and calls `analyze`. Returns early with `error.set(...)` if neither `lastRequest` nor an explicit `page`/`promptType` override is present.
- Tests in `ai-analysis.service.spec.ts`:
  - hasActiveConversation false initially.
  - analyze on success: assistant turn appended, lastRequest set.
  - analyze on error: no assistant turn appended, lastRequest stays null.
  - appendUserTurnAndAnalyze: appends one user + one assistant turn after a prior round.
  - appendUserTurnAndAnalyze without prior request and no overrides: error is set.
  - resetConversation clears all state.

### 3. Modal adaptation

- Replace local `conversationHistory` signal with `computed(() => aiService.conversationHistory())`.
- `ngOnInit` only calls `sendInitialRequest()` when `hasActiveConversation()` is false.
- Remove all local `conversationHistory.set/update` calls — the service owns history now.
- Add a "Start new chat" button to the modal header that calls `resetConversation()` then `sendInitialRequest()`.
- The HTML template iterates over `conversationHistory()` exactly as before — it works with both `signal` and `computed`.

### 4. Explore page wiring

- `explore.component.ts`: inject `AiAnalysisService` (public) and `MatDialog`.
- Add `truncatedChartData()` returning `{ columns, rows, totalRows, returnedRows, truncated }` slicing at `EXPLORE_AI_ROW_CAP`.
- Add `descriptorSummary()` producing a short string from `query()` and the active range config.
- Add `addToNewChat()`: `resetConversation()` then `dialog.open(AiAnalysisModalComponent, { data })` with `metadata.exploreDescriptor = query()`.
- Add `addToCurrentChat()`: bail when `!hasActiveConversation()`; call `appendUserTurnAndAnalyze(userTurn, { chartData, metadata, page: 'explore', promptType: 'summarize_trends' })`; then open the modal.
- `explore.component.html`: add `mat-stroked-button [matMenuTriggerFor]="addAiMenu" *ngIf="result()"` to `.run-controls`, with two `mat-menu-item`s. The "Add to current chat" item is `[disabled]="!aiService.hasActiveConversation()"`.

### 5. Component tests

`explore.component.spec.ts`:
- Stubs for `ExploreService`, `RegistryDashService`, `AiAnalysisService`, `MatDialog`.
- Asserts:
  - "Add to AI Chat" hidden when result is null; visible after `result.set(...)`.
  - `addToNewChat()` calls `resetConversation()` and `dialog.open` with `data.metadata.exploreDescriptor` set.
  - `addToCurrentChat()` is a no-op when `hasActiveConversation` is false.
  - `addToCurrentChat()` calls `appendUserTurnAndAnalyze` with `page='explore'` and an `exploreDescriptor` in metadata, then opens the dialog when a chat is active.
  - `truncatedChartData()` truncates at 100 rows and reports `truncated: true` when the source has more.

### 6. Test plan

Append **Test 21** to `.claude/plugins/ud-registry-dash/skills/test-registry-dash/test-plan.md` covering both golden paths and the edge cases (button hidden before query, menu item disabled with no prior conversation, truncation hint above 100 rows).

### 7. Verification

- `cd console-webapp && nvm use 22.16.0 && npm run build`
- `cd console-webapp && nvm use 22.16.0 && ng test --browsers=ChromeHeadless --watch=false`
- Manual e2e per Test 21.
- `git diff master -- core/` is empty (no backend changes).

## PR

Open against `unstoppabledomains/nomulus:master`. Reference SRE-1942.
