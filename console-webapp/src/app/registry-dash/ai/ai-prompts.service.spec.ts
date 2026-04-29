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

import { TestBed } from '@angular/core/testing';
import { AiPromptsService } from './ai-prompts.service';

describe('AiPromptsService', () => {
  let service: AiPromptsService;
  let fetchSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AiPromptsService);
    fetchSpy = spyOn(window, 'fetch');
  });

  it('fetches and returns the menu for a page', async () => {
    fetchSpy.and.resolveTo(
      new Response(
        JSON.stringify({
          version: 'v1',
          menu: [
            {
              promptType: 'summarize_trends',
              label: 'Summarize trends',
              icon: 'bar_chart',
              userMessage: '...',
            },
          ],
        }),
      ),
    );
    const result = await service.getMenu('portfolio');
    expect(result.version).toBe('v1');
    expect(result.menu.length).toBe(1);
    expect(fetchSpy).toHaveBeenCalledWith(
      '/console-api/registry-dash/ai/prompts?page=portfolio',
      jasmine.objectContaining({ credentials: 'include' }),
    );
  });

  it('caches results so the second call does not refetch', async () => {
    fetchSpy.and.resolveTo(new Response(JSON.stringify({ version: 'v1', menu: [] })));
    await service.getMenu('portfolio');
    await service.getMenu('portfolio');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('falls back to FALLBACK_MENU on fetch error', async () => {
    fetchSpy.and.resolveTo(new Response('boom', { status: 500 }));
    const result = await service.getMenu('portfolio');
    expect(result.version).toBe('fallback');
    expect(result.menu.length).toBeGreaterThan(0);
  });
});
