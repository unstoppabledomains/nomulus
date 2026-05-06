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
  ChatTimelineEntry,
  ConversationMessage,
  TOOL_LABELS,
  ToolMessage,
  ToolStatus,
  toWireHistory,
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

  /**
   * Persistent chronological timeline of the chat: user/assistant turns
   * and tool-call entries (SRE-1963), interleaved in arrival order. Tool
   * entries persist in scrollback after the response completes so the
   * user can see every tool that ran.
   */
  conversationHistory = signal<ChatTimelineEntry[]>([]);
  lastRequest = signal<LastRequestShape | null>(null);
  hasActiveConversation = computed(() => this.conversationHistory().length > 0);

  private abortController: AbortController | null = null;

  resetTransientState(): void {
    this.streaming.set(false);
    this.streamedText.set('');
    this.error.set(null);
  }

  /**
   * Clear stale visible state (error, partial streamed text) left over
   * from a previous, completed session — but ONLY when nothing is
   * currently streaming. This is safe to call from a modal's `ngOnInit`
   * even when a request was kicked off just before the dialog opened
   * (e.g. ExploreComponent.addToCurrentChat → analyze → dialog.open):
   * if `streaming` is true, this is a no-op so we don't flip it back to
   * false mid-stream and re-enable the follow-up input.
   */
  clearStaleDisplayState(): void {
    if (this.streaming()) return;
    this.streamedText.set('');
    this.error.set(null);
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
   * The analyze() finally-block will set streaming to false cleanly and
   * resolve any IN_FLIGHT tool entries to a terminal state.
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

    // The incoming request carries the authoritative wire conversation up
    // to and including the new user turn. Smart-merge it into the display
    // timeline so any persisted tool entries (SRE-1963) from prior turns
    // survive — appending only the new tail when the existing wire-
    // projection is a strict prefix; otherwise (fresh seed or external
    // reset) replacing wholesale.
    const currentWire = toWireHistory(this.conversationHistory());
    const incomingWire = request.conversationHistory;
    const isPrefix =
      incomingWire.length >= currentWire.length &&
      currentWire.every(
        (e, i) =>
          incomingWire[i] !== undefined &&
          incomingWire[i].role === e.role &&
          incomingWire[i].content === e.content,
      );
    if (isPrefix) {
      const tail = incomingWire.slice(currentWire.length);
      if (tail.length > 0) {
        this.conversationHistory.update(h => [...h, ...tail]);
      }
    } else {
      this.conversationHistory.set([...incomingWire]);
    }

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
        // dispatch into the (now-stale) conversationHistory/streamedText.
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
            console.debug(
              '[ai-chat] frame',
              typed.type,
              typed.type === 'tool_use'
                ? typed.tool
                : typed.type === 'tool_result'
                ? `${typed.tool}/${typed.status ?? typed.ok}`
                : '',
            );
            if (typed.type === 'text') {
              accumulated += typed.text;
              this.streamedText.set(accumulated);
            } else if (typed.type === 'tool_use') {
              // Commit any text accumulated before the tool call so the
              // chronological order text → tool → text is preserved in
              // the persistent timeline. Without this, all turn-0 text
              // would land AFTER the tool entries when the stream ends.
              if (accumulated) {
                const text = accumulated;
                accumulated = '';
                this.streamedText.set('');
                this.conversationHistory.update(h => [
                  ...h,
                  { role: 'assistant', content: text },
                ]);
              }
              const label = TOOL_LABELS[typed.tool] ?? `🔧 Running ${typed.tool}`;
              const entry: ToolMessage = {
                role: 'tool',
                tool: typed.tool,
                label,
                status: 'IN_FLIGHT',
                ok: false,
              };
              this.conversationHistory.update(h => [...h, entry]);
            } else if (typed.type === 'tool_result') {
              // Defensive defaults: older backends (or replayed fixtures) may
              // omit `status`; treat missing status as OK iff `ok` is true.
              const status: ToolStatus = typed.status ?? (typed.ok ? 'OK' : 'INTERNAL_ERROR');
              this.conversationHistory.update(h =>
                updateLatestInFlight(h, typed.tool, {
                  status,
                  ok: typed.ok,
                  diagnostic: typed.diagnostic,
                }),
              );
            }
            // 'done' is informational; the [DONE] sentinel still terminates the loop.
          } catch (e) {
            console.warn('[ai-chat] dropped malformed SSE frame', { data, error: e });
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
        if (accumulated) {
          this.conversationHistory.update(h => [
            ...h,
            { role: 'assistant', content: accumulated },
          ]);
        }
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
      // freshly-set streaming state when its finally runs.
      if (this.abortController === controller) {
        // Resolve any IN_FLIGHT tool entries left over so the pill stops
        // pulsing. Use a diagnostic that matches the actual failure mode:
        // user-initiated abort gets "Cancelled"; network drop / 5xx /
        // unexpected stream end get "Interrupted" so the pill doesn't
        // misrepresent the failure.
        const diagnostic = controller.signal.aborted ? 'Cancelled' : 'Interrupted';
        this.conversationHistory.update(h => terminateInFlight(h, diagnostic));
        this.streaming.set(false);
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

    // Tool entries in the timeline are UI-only — strip them before
    // sending the wire history to the backend.
    const newHistory: ConversationMessage[] = [
      ...toWireHistory(this.conversationHistory()),
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

/**
 * Returns a new timeline with the OLDEST `IN_FLIGHT` entry for `tool`
 * resolved with the given patch. The backend emits `tool_result` frames
 * in the same FIFO order as their `tool_use` blocks, so when the same
 * tool is invoked multiple times in a single turn we need to match
 * results to pills oldest-first; LIFO matching would swap the
 * statuses/diagnostics of duplicate invocations.
 */
function updateLatestInFlight(
  timeline: ChatTimelineEntry[],
  tool: string,
  patch: { status: ToolStatus; ok: boolean; diagnostic?: string },
): ChatTimelineEntry[] {
  for (let i = 0; i < timeline.length; i++) {
    const e = timeline[i];
    if (e.role === 'tool' && e.tool === tool && e.status === 'IN_FLIGHT') {
      const out = timeline.slice();
      out[i] = { ...e, ...patch };
      return out;
    }
  }
  return timeline;
}

/**
 * Resolve any leftover `IN_FLIGHT` tool entries to a terminal state so
 * the pill stops pulsing on cancel/drop. Caller passes the diagnostic
 * to attach (e.g. "Cancelled" on user-initiated abort, "Interrupted"
 * on network drop / 5xx) so the pill doesn't misrepresent the failure
 * mode.
 */
function terminateInFlight(
  timeline: ChatTimelineEntry[],
  diagnostic: string,
): ChatTimelineEntry[] {
  let mutated: ChatTimelineEntry[] | null = null;
  for (let i = 0; i < timeline.length; i++) {
    const e = timeline[i];
    if (e.role === 'tool' && e.status === 'IN_FLIGHT') {
      if (!mutated) mutated = timeline.slice();
      mutated[i] = {
        ...e,
        status: 'INTERNAL_ERROR',
        ok: false,
        diagnostic: e.diagnostic ?? diagnostic,
      };
    }
  }
  return mutated ?? timeline;
}
