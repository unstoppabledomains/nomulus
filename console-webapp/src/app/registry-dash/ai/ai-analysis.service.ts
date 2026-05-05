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

import { Injectable, computed, signal } from '@angular/core';
import {
  AiAnalyzeRequest,
  AiStreamFrame,
  ConversationMessage,
  TOOL_LABELS,
  ToolInFlight,
} from './ai-analysis.models';

type LastRequestShape = Pick<
  AiAnalyzeRequest,
  'page' | 'promptType' | 'metadata' | 'systemPrompt' | 'model'
>;

@Injectable({ providedIn: 'root' })
export class AiAnalysisService {
  streaming = signal(false);
  streamedText = signal('');
  error = signal<string | null>(null);
  toolsInFlight = signal<ToolInFlight[]>([]);
  toolsUsed = signal<string[]>([]);

  conversationHistory = signal<ConversationMessage[]>([]);
  lastRequest = signal<LastRequestShape | null>(null);
  hasActiveConversation = computed(() => this.conversationHistory().length > 0);

  private abortController: AbortController | null = null;

  resetTransientState(): void {
    this.streaming.set(false);
    this.streamedText.set('');
    this.error.set(null);
    this.toolsInFlight.set([]);
    this.toolsUsed.set([]);
  }

  /**
   * Clear stale visible state (error, partial streamed text, tool chips)
   * left over from a previous, completed session — but ONLY when nothing
   * is currently streaming. This is safe to call from a modal's `ngOnInit`
   * even when a request was kicked off just before the dialog opened
   * (e.g. ExploreComponent.addToCurrentChat → analyze → dialog.open):
   * if `streaming` is true, this is a no-op so we don't flip it back to
   * false mid-stream and re-enable the follow-up input.
   */
  clearStaleDisplayState(): void {
    if (this.streaming()) return;
    this.streamedText.set('');
    this.error.set(null);
    this.toolsInFlight.set([]);
    this.toolsUsed.set([]);
  }

  /**
   * Public setter for clearing the error signal. Consumers (e.g. the
   * modal's retryAfterError flow) should use this rather than reaching
   * into `error.set(null)` directly so the service retains ownership of
   * its own state.
   */
  clearError(): void {
    this.error.set(null);
  }

  resetConversation(): void {
    this.abortController?.abort();
    this.abortController = null;
    this.conversationHistory.set([]);
    this.lastRequest.set(null);
    this.resetTransientState();
  }

  /**
   * Cancel the in-flight stream without disturbing conversation state.
   * Aborts the active request and clears any error signal so an
   * intentional interrupt does not surface as an error to the user.
   * The analyze() finally-block will set streaming to false cleanly.
   */
  cancel(): void {
    this.abortController?.abort();
    this.error.set(null);
  }

  async analyze(request: AiAnalyzeRequest): Promise<void> {
    this.abortController?.abort();
    const controller = new AbortController();
    this.abortController = controller;

    this.streaming.set(true);
    this.streamedText.set('');
    this.error.set(null);
    this.toolsInFlight.set([]);
    this.toolsUsed.set([]);

    // The incoming request carries the authoritative conversation up to and
    // including the new user turn. Capture it now so the modal renders the
    // user turn immediately while the assistant response streams in.
    this.conversationHistory.set([...request.conversationHistory]);

    try {
      const response = await fetch('/console-api/registry-dash/ai/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        credentials: 'same-origin',
        signal: controller.signal,
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
        // Defensive abort check: if the response is fully buffered before
        // abort, reader.read() may resolve with cached chunks before the
        // abort propagates — without these breaks, those chunks would still
        // dispatch to the (now-stale) toolsUsed/streamedText signals.
        // Re-check after the await as well: reader.read() can resolve with
        // a cached chunk between the pre-await check and the resume here.
        if (controller.signal.aborted) break;
        const { done, value } = await reader.read();
        if (controller.signal.aborted) break;
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

      // Only commit history/lastRequest if THIS controller is still the
      // active one and was not aborted. Otherwise we'd resurrect stale
      // state after `resetConversation()` or a superseding `analyze()`
      // call (e.g. assistant reply from a cancelled request appearing in
      // a freshly-started conversation).
      const isStillActive =
        this.abortController === controller && !controller.signal.aborted;
      if (isStillActive && !this.error()) {
        this.conversationHistory.update(h => [
          ...h,
          { role: 'assistant', content: accumulated },
        ]);
        this.lastRequest.set({
          page: request.page,
          promptType: request.promptType,
          metadata: request.metadata,
          systemPrompt: request.systemPrompt,
          model: request.model,
        });
      }
    } catch (e) {
      // Aborted requests (modal close, reset, replaced by a newer request) are
      // expected; don't surface them as user-visible interruptions.
      if (!controller.signal.aborted) {
        this.error.set('Response interrupted. Try again?');
      }
    } finally {
      // Only clear streaming UI state if this controller is still the active
      // one — otherwise an aborted older call would clobber the newer call's
      // freshly-set streaming/toolsInFlight when its finally runs.
      if (this.abortController === controller) {
        this.streaming.set(false);
        this.toolsInFlight.set([]);
        this.abortController = null;
      }
    }
  }

  async appendUserTurnAndAnalyze(
    content: string,
    overrides: Partial<Omit<AiAnalyzeRequest, 'conversationHistory'>>,
  ): Promise<void> {
    const last = this.lastRequest();
    const page = overrides.page ?? last?.page;
    const promptType = overrides.promptType ?? last?.promptType;
    if (!page || !promptType) {
      this.error.set('Cannot continue conversation: no prior request context.');
      return;
    }

    const metadata = overrides.metadata ?? last?.metadata ?? {
      filteredTlds: [],
      filteredRegistrars: [],
    };
    const systemPrompt = overrides.systemPrompt ?? last?.systemPrompt;
    const model = overrides.model ?? last?.model;
    const chartData = overrides.chartData;

    const newHistory: ConversationMessage[] = [
      ...this.conversationHistory(),
      { role: 'user', content },
    ];

    await this.analyze({
      page,
      promptType,
      metadata,
      chartData,
      model,
      systemPrompt,
      conversationHistory: newHistory,
    });
  }
}

function removeFirst<T>(list: T[], pred: (item: T) => boolean): T[] {
  const idx = list.findIndex(pred);
  if (idx < 0) return list;
  const out = list.slice();
  out.splice(idx, 1);
  return out;
}
