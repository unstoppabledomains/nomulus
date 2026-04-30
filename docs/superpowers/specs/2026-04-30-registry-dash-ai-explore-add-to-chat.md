# Registry Dashboard AI — "Add to AI Chat" from Data Exploration

**Date:** 2026-04-30
**Author:** torrey@unstoppabledomains.com
**Status:** Implemented (frontend-only, no backend changes)
**Linear:** SRE-1942
**Predecessors:** Tier 1 (PR #114), Tier 2 (PR #121), Tier 3 v1

## Context

A registry analyst on an AI chat thread (e.g. Domain Activity) currently has to close the modal, navigate to **Data Exploration**, build a query, and re-open AI on Explore — losing the prior conversation and re-stating context. This change adds an **"Add to AI Chat"** affordance to the Explore page so a query result can either start a new chat or be appended to the user's existing conversation.

The existing `/console-api/registry-dash/ai/analyze` endpoint already handles multi-turn conversations and arbitrary `chartData`. This is a frontend-only change.

## Scope

In scope:

- Lift conversation state from `AiAnalysisModalComponent` into `AiAnalysisService` so it survives modal close.
- Add an `Add to AI Chat` split button on `ExploreComponent` with two actions: `Start new chat` and `Add to current chat`.
- Inline the `ExploreQueryDescriptor` and a row sample into the user-turn text (no backend changes — `metadata` keys other than `dateRange`/`filteredTlds` are ignored server-side today).
- Cap the attached row payload at 100 rows and surface the truncation hint in the user turn.
- Add a `Start new chat` control to the modal header so users can deliberately reseed.

Out of scope (covered by follow-up prompts):

- Persistence of conversation state across page reload (`localStorage`).
- Cross-tab synchronisation (`BroadcastChannel`).
- Server-side rendering of `metadata.exploreDescriptor` into the system prompt.
- Introducing a new `analyze_explore_query` `promptType` — we reuse `summarize_trends`.
- Persistent floating "Resume chat" widget.

## Architecture

### Frontend (only)

```
console-webapp/src/app/registry-dash/
├── ai/
│   ├── ai-analysis.service.ts          // owns conversationHistory + lastRequest
│   ├── ai-analysis-modal.component.ts  // reads history from service
│   ├── ai-analysis.models.ts           // EXPLORE_AI_ROW_CAP, metadata JSDoc
│   └── ...
└── explore/
    ├── explore.component.ts            // addToNewChat / addToCurrentChat
    └── explore.component.html          // mat-stroked-button + mat-menu
```

### Service contract

`AiAnalysisService`:

- `conversationHistory: WritableSignal<ConversationMessage[]>` — full transcript.
- `lastRequest: WritableSignal<Pick<AiAnalyzeRequest, 'page' | 'promptType' | 'metadata' | 'systemPrompt' | 'model'> | null>` — replay-able request shape.
- `hasActiveConversation = computed(() => conversationHistory().length > 0)`.
- `resetConversation()` — clears history, lastRequest, streamedText, error, tools.
- `analyze(request)` — captures the incoming `request.conversationHistory` immediately (so the user turn renders instantly), appends the assistant turn on success, captures `lastRequest` on success.
- `appendUserTurnAndAnalyze(content, overrides)` — pushes a user turn, merges with current history + `lastRequest` defaults + overrides, calls `analyze`.

### Modal contract changes

- `conversationHistory` becomes a `computed` over the service signal. The modal stops mutating history directly.
- `ngOnInit` only fires the seed request when `aiService.hasActiveConversation()` is false.
- New header button `Start new chat` calls `resetConversation()` then `sendInitialRequest()`.

### User-turn payload (Explore -> chat)

```text
I just ran an Explore query: <descriptorSummary>.

Descriptor: <ExploreQuery JSON>

Data (<returnedRows> of <totalRows> rows[, truncated]): <rows JSON>
```

`chartData` is also attached as `{ columns, rows: rows.slice(0, 100), totalRows, returnedRows, truncated }`. The descriptor is also placed in `metadata.exploreDescriptor` for forward-compatibility — the backend ignores it today (see follow-up #3).

## Testing

- New `ai-analysis.service.spec.ts` — covers user/assistant turn capture, error path, `appendUserTurnAndAnalyze`, `resetConversation`, `hasActiveConversation`.
- New `explore.component.spec.ts` — button visibility, menu enablement, dialog data shape, truncation behaviour.
- Manual e2e: see Test 21 in `.claude/plugins/ud-registry-dash/skills/test-registry-dash/test-plan.md`.

## Risks & mitigations

- **Modal double-append regression** — old modal appended assistant turns externally; we now do it in the service. Mitigation: modal no longer mutates history; service is the single writer. Covered by `ai-analysis.service.spec.ts`.
- **Conversation leakage across pages** — service is `providedIn: 'root'`, so history persists across navigation. This is the intended new behaviour but means a user closing the modal mid-conversation still sees the prior turns when re-opening from a sparkle. The header `Start new chat` button is the explicit reset.
- **Payload size** — capped at 100 rows + descriptor JSON; comparable in size to existing `chartData` payloads on other pages.

## Follow-ups

- `.context/tier3-followup-explore-add-to-chat-persistence-prompt.md`
- `.context/tier3-followup-explore-add-to-chat-multitab-prompt.md`
- `.context/tier3-followup-explore-add-to-chat-backend-descriptor-prompt.md`
