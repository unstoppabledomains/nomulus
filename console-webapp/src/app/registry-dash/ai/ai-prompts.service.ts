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

import { Injectable } from '@angular/core';
import { AiPromptOption } from './ai-analysis.models';
import { FALLBACK_MENU } from './ai-prompts';

export interface AiPromptsResponse {
  version: string;
  menu: AiPromptOption[];
}

@Injectable({ providedIn: 'root' })
export class AiPromptsService {
  private cache = new Map<string, AiPromptsResponse>();

  async getMenu(page: string): Promise<AiPromptsResponse> {
    const cached = this.cache.get(page);
    if (cached) return cached;

    try {
      const res = await fetch(
        `/console-api/registry-dash/ai/prompts?page=${encodeURIComponent(page)}`,
        { credentials: 'include' },
      );
      if (!res.ok) throw new Error(`status ${res.status}`);
      const data = (await res.json()) as AiPromptsResponse;
      this.cache.set(page, data);
      return data;
    } catch {
      return { version: 'fallback', menu: FALLBACK_MENU[page] ?? [] };
    }
  }
}
