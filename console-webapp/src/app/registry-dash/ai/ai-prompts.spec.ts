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

import {
  ASK_ANYTHING_PROMPT,
  DOMAIN_ACTIVITY_PROMPTS,
  EXPLORE_PROMPTS,
  FALLBACK_MENU,
  FORECASTING_PROMPTS,
  OVERVIEW_PROMPTS,
  PORTFOLIO_PROMPTS,
  PRICING_PROMPTS,
  REVENUE_BILLING_PROMPTS,
} from './ai-prompts';

describe('ai-prompts', () => {
  describe('ASK_ANYTHING_PROMPT', () => {
    it('has an empty userMessage so the modal does not auto-fire', () => {
      expect(ASK_ANYTHING_PROMPT.userMessage).toBe('');
    });

    it('uses the ask_anything promptType so the backend can route it', () => {
      expect(ASK_ANYTHING_PROMPT.promptType).toBe('ask_anything');
    });

    it('has a non-empty label and icon', () => {
      expect(ASK_ANYTHING_PROMPT.label.length).toBeGreaterThan(0);
      expect(ASK_ANYTHING_PROMPT.icon.length).toBeGreaterThan(0);
    });
  });

  describe('preset menus', () => {
    const allMenus: Array<[string, typeof DOMAIN_ACTIVITY_PROMPTS]> = [
      ['DOMAIN_ACTIVITY_PROMPTS', DOMAIN_ACTIVITY_PROMPTS],
      ['REVENUE_BILLING_PROMPTS', REVENUE_BILLING_PROMPTS],
      ['FORECASTING_PROMPTS', FORECASTING_PROMPTS],
      ['EXPLORE_PROMPTS', EXPLORE_PROMPTS],
      ['OVERVIEW_PROMPTS', OVERVIEW_PROMPTS],
      ['PORTFOLIO_PROMPTS', PORTFOLIO_PROMPTS],
      ['PRICING_PROMPTS', PRICING_PROMPTS],
    ];

    allMenus.forEach(([name, menu]) => {
      it(`${name} ends with ASK_ANYTHING_PROMPT (last entry)`, () => {
        expect(menu[menu.length - 1]).toBe(ASK_ANYTHING_PROMPT);
      });

      it(`${name} contains ASK_ANYTHING_PROMPT exactly once`, () => {
        const matches = menu.filter(p => p === ASK_ANYTHING_PROMPT);
        expect(matches.length).toBe(1);
      });

      it(`${name} has at least one preset before ASK_ANYTHING_PROMPT`, () => {
        // Presets are the preferred path; ask-anything is the fallback.
        expect(menu.length).toBeGreaterThan(1);
      });
    });
  });

  describe('FALLBACK_MENU', () => {
    it('exposes ask-anything for every page key', () => {
      for (const [page, menu] of Object.entries(FALLBACK_MENU)) {
        expect(menu[menu.length - 1])
          .withContext(`page: ${page}`)
          .toBe(ASK_ANYTHING_PROMPT);
      }
    });
  });
});
