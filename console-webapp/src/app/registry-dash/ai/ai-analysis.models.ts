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

export type AiStreamFrame =
  | { type: 'text'; text: string }
  | { type: 'tool_use'; tool: string; args: Record<string, unknown> }
  | { type: 'tool_result'; tool: string; ok: boolean }
  | { type: 'done' };

export interface ToolInFlight {
  tool: string;
  label: string;
}

export const TOOL_LABELS: Record<string, string> = {
  query_transfers: '🔍 Searching transfers',
  get_pricing_rules: '💰 Looking up pricing',
  query_registrar_activity: '📊 Checking registrar activity',
  query_domain_details: '🔎 Looking up domain',
};
