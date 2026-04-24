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

import { Injectable, signal } from '@angular/core';
import { AiAnalyzeRequest } from './ai-analysis.models';

@Injectable({ providedIn: 'root' })
export class AiAnalysisService {
  streaming = signal(false);
  streamedText = signal('');
  error = signal<string | null>(null);

  async analyze(request: AiAnalyzeRequest): Promise<void> {
    this.streaming.set(true);
    this.streamedText.set('');
    this.error.set(null);

    try {
      const xsrfCookie = document.cookie
        .split('; ')
        .find(c => c.startsWith('X-CSRF-Token='));
      const xsrfToken = xsrfCookie?.split('=')[1] ?? '';

      const response = await fetch('/console-api/registry-dash/ai/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        credentials: 'same-origin',
      });

      if (response.status === 429) {
        const retryAfter = response.headers.get('Retry-After');
        const minutes = retryAfter ? Math.ceil(parseInt(retryAfter, 10) / 60) : 5;
        this.error.set(`Analysis limit reached. Try again in ${minutes} minutes.`);
        return;
      }
      if (response.status === 502) {
        this.error.set('Analysis temporarily unavailable. Please try again.');
        return;
      }
      if (response.status === 503) {
        this.error.set('AI service is busy. Please try again shortly.');
        return;
      }
      if (!response.ok) {
        this.error.set('Analysis failed. Please try again.');
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        this.error.set('Streaming not supported.');
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';
      let accumulated = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          const data = line.substring(6).trim();
          if (data === '[DONE]') break;
          try {
            const parsed = JSON.parse(data);
            if (parsed.text) {
              accumulated += parsed.text;
              this.streamedText.set(accumulated);
            }
          } catch {
            // skip malformed chunks
          }
        }
      }
    } catch (e) {
      this.error.set('Response interrupted. Try again?');
    } finally {
      this.streaming.set(false);
    }
  }
}
