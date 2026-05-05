// Copyright 2026 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

export interface AiAnalyzeRequest {
  page:
    | 'domain-activity'
    | 'revenue-billing'
    | 'forecasting'
    | 'explore'
    | 'overview'
    | 'portfolio'
    | 'pricing';
  promptType: string;
  /**
   * Free-form context bag forwarded to the backend. Well-known keys:
   * - `dateRange` (required): `{ start: string; end: string }`.
   * - `granularity`: optional bucket size e.g. `'DAY'`.
   * - `filteredTlds` (required): list of TLD names currently selected.
   * - `filteredRegistrars` (required): list of registrar IDs currently selected.
   * - `exploreDescriptor`: optional `ExploreQuery`-shaped object describing the
   *   query that produced `chartData` when the request originated from the
   *   Data Exploration page. Currently inlined into the user-turn text by the
   *   frontend; the backend ignores unknown keys.
   * Additional keys are allowed and silently passed through.
   */
  metadata: {
    dateRange: { start: string; end: string };
    granularity?: string;
    filteredTlds: string[];
    filteredRegistrars: string[];
    [key: string]: any;
  };
  chartData: any;
  model?: string;
  systemPrompt?: string;
  conversationHistory: ConversationMessage[];
}

/** Maximum number of Explore rows attached to an AI request. */
export const EXPLORE_AI_ROW_CAP = 100;

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface AiPromptOption {
  icon: string;
  label: string;
  promptType: string;
  userMessage: string;
}

export type AiModelChoice = 'haiku' | 'sonnet' | 'opus';

export type ToolStatus =
  | 'OK'
  | 'EMPTY_FOR_RANGE'
  | 'INVALID_ARGS'
  | 'OUT_OF_RANGE'
  | 'PERMISSION_DENIED'
  | 'INTERNAL_ERROR';

export type AiStreamFrame =
  | { type: 'text'; text: string }
  | { type: 'tool_use'; tool: string; args: Record<string, unknown> }
  | {
      type: 'tool_result';
      tool: string;
      ok: boolean;
      status: ToolStatus;
      diagnostic?: string;
    }
  | { type: 'done' };

export interface ToolInFlight {
  tool: string;
  label: string;
}

export interface ToolCompleted {
  tool: string;
  label: string;
  status: ToolStatus;
  diagnostic?: string;
  ok: boolean;
}

/**
 * Short chip text shown next to a completed tool when its status is not OK.
 * `null` means render no chip (silent success). The diagnostic from the frame
 * is surfaced separately as a tooltip.
 */
export const TOOL_STATUS_CHIPS: Record<ToolStatus, { text: string; tone: 'warn' | 'error' } | null> =
  {
    OK: null,
    EMPTY_FOR_RANGE: { text: 'No data', tone: 'warn' },
    OUT_OF_RANGE: { text: 'Out of range', tone: 'warn' },
    INVALID_ARGS: { text: 'Invalid args', tone: 'error' },
    PERMISSION_DENIED: { text: 'No access', tone: 'error' },
    INTERNAL_ERROR: { text: 'Tool error', tone: 'error' },
  };

export const TOOL_LABELS: Record<string, string> = {
  query_transfers: '🔍 Searching transfers',
  get_pricing_rules: '💰 Looking up pricing',
  query_registrar_activity: '📊 Checking registrar activity',
  query_domain_details: '🔎 Looking up domain',
  get_registrar_details: '🏢 Looking up registrar',
  get_tld_config: '⚙️ Looking up TLD config',
  query_revenue_breakdown: '💵 Breaking down revenue',
  query_renewal_rates: '🔄 Checking renewal rates',
  query_expiration_curve: '📉 Mapping expirations',
  run_explore_query: '🔬 Running data query',
};
