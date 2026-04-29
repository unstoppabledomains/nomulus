# Registry Dashboard AI — Tier 2 & 3 Design Notes

Captured 2026-04-24 during Tier 1 polish session. These are early design notes to inform future specs.

## Tier Overview

- **Tier 1** (complete, PR #114): Static snapshot analysis. Sparkle button → prompt menu → streaming modal with follow-ups. Claude gets a JSON dump of chart data. Three pages: Domain Activity, Revenue Billing, Forecasting.
- **Tier 2**: Expanded pages + prompt refinement.
- **Tier 3**: Agentic tool use — Claude can fetch additional data mid-conversation.

## Tier 2 — Expanded Pages + Prompt Refinement

- Add sparkle buttons to remaining pages (Overview, Portfolio, Pricing, Explore)
- Refine system prompts based on Tier 1 usage patterns
- Move finalized prompts from frontend constants to backend config (the "migration path" from the Tier 1 spec)
- Add prompt versioning/A-B testing capability
- Model preference persistence (already partially implemented via settings)

## Tier 3 — Agentic Tool Use

**Why:** Tier 1 gives Claude a static snapshot of chart data. Follow-up questions like "what domains transferred between which registrars?" fail because the detail isn't in the dataset. Tool use solves this.

- Claude gets tools it can call to fetch additional data mid-conversation
- Uses Anthropic tool_use API (function calling)
- Backend defines available tools, Claude decides when to invoke them
- Example tools:
  - `query_transfers(tld, date_range)` → domain-level transfer details with registrar info
  - `query_registrar_activity(registrar_id)` → per-registrar breakdown
  - `query_domain_details(domain_name)` → full lifecycle for a specific domain
  - `run_explore_query(query_descriptor)` → runs against the Explore engine (same as Data Exploration page)
  - `get_pricing_rules(tld, registrar_id)` → current pricing configuration
- SSE still sufficient (request-response with tool calls in between)
- Backend orchestrates: receives user message → sends to Anthropic with tools → if tool_use response, executes tool, sends result back → continues until text response
- Multi-turn tool use loop happens server-side, streamed text chunks sent to frontend as they arrive
