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
import {
  AiAnalyzeRequest,
  AiStreamFrame,
  TOOL_LABELS,
  ToolInFlight,
} from './ai-analysis.models';

@Injectable({ providedIn: 'root' })
export class AiAnalysisService {
  streaming = signal(false);
  streamedText = signal('');
  error = signal<string | null>(null);
  toolsInFlight = signal<ToolInFlight[]>([]);
  toolsUsed = signal<string[]>([]);

  async analyze(request: AiAnalyzeRequest): Promise<void> {
    this.streaming.set(true);
    this.streamedText.set('');
    this.error.set(null);
    this.toolsInFlight.set([]);
    this.toolsUsed.set([]);

    try {
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
            const frame = JSON.parse(data) as AiStreamFrame | { text?: string };

            // Tier 1 (legacy) frames had only {text: "..."} — handle gracefully.
            if (!('type' in frame) && 'text' in frame && frame.text) {
              accumulated += frame.text;
              this.streamedText.set(accumulated);
              continue;
            }

            const typed = frame as AiStreamFrame;
            if (typed.type === 'text') {
              accumulated += typed.text;
              this.streamedText.set(accumulated);
            } else if (typed.type === 'tool_use') {
              const label = TOOL_LABELS[typed.tool] ?? `🔧 Running ${typed.tool}`;
              this.toolsInFlight.update(list => [...list, { tool: typed.tool, label }]);
              this.toolsUsed.update(list => [...list, typed.tool]);
            } else if (typed.type === 'tool_result') {
              this.toolsInFlight.update(list =>
                removeFirst(list, t => t.tool === typed.tool),
              );
            }
            // 'done' is informational; the [DONE] sentinel still terminates the loop.
          } catch {
            // skip malformed chunks
          }
        }
      }
    } catch (e) {
      this.error.set('Response interrupted. Try again?');
    } finally {
      this.streaming.set(false);
      this.toolsInFlight.set([]);
    }
  }
}

function removeFirst<T>(list: T[], pred: (item: T) => boolean): T[] {
  const idx = list.findIndex(pred);
  if (idx < 0) return list;
  const out = list.slice();
  out.splice(idx, 1);
  return out;
}
