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

import { AiPromptOption } from './ai-analysis.models';

export const DOMAIN_ACTIVITY_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage:
      'Summarize the key trends in domain activity — lifecycle patterns, growth or decline across TLDs.',
  },
  {
    icon: 'search',
    label: 'Find anomalies',
    promptType: 'find_anomalies',
    userMessage:
      'Identify anomalies in domain activity — unexpected spikes, unusual create/delete ratios, outlier TLDs.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage:
      'Based on this domain activity data, suggest specific actions for retention and growth.',
  },
];

export const REVENUE_BILLING_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage:
      'Summarize revenue trends — key drivers, growth percentages, TLD performance comparison.',
  },
  {
    icon: 'search',
    label: 'Find anomalies',
    promptType: 'find_anomalies',
    userMessage:
      'Identify revenue anomalies — unexpected spikes or drops, declining segments, unusual patterns.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage:
      'Based on this revenue data, suggest pricing adjustments, registrar outreach, or growth opportunities.',
  },
];

export const FORECASTING_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage:
      'Summarize renewal health — overall rates, TLD comparison, trajectory.',
  },
  {
    icon: 'warning',
    label: 'Identify risks',
    promptType: 'identify_risks',
    userMessage:
      'Identify risks — expiration cliffs, declining registrars, TLDs with dropping renewal rates.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage:
      'Suggest retention strategies, pricing recommendations, and proactive outreach based on this forecast data.',
  },
];

export const EXPLORE_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage: 'Summarize the key trends visible in this data.',
  },
  {
    icon: 'search',
    label: 'Find anomalies',
    promptType: 'find_anomalies',
    userMessage: 'Identify any anomalies or unusual patterns in this data.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage: 'Based on this data, what actions would you recommend?',
  },
];

export const OVERVIEW_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage:
      'Summarize the key trends across the registry — activity patterns, renewal health, and overall performance.',
  },
  {
    icon: 'search',
    label: 'Find anomalies',
    promptType: 'find_anomalies',
    userMessage: 'Identify any anomalies or concerns in the overview metrics.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage:
      'Based on these overview metrics, what should the registry team focus on?',
  },
];

export const PROMPTS_BY_PAGE: Record<string, AiPromptOption[]> = {
  'domain-activity': DOMAIN_ACTIVITY_PROMPTS,
  'revenue-billing': REVENUE_BILLING_PROMPTS,
  forecasting: FORECASTING_PROMPTS,
  explore: EXPLORE_PROMPTS,
  overview: OVERVIEW_PROMPTS,
};
