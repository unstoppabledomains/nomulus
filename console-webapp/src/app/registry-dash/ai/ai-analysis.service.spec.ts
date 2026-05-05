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
import { AiAnalysisService } from './ai-analysis.service';
import { AiAnalyzeRequest } from './ai-analysis.models';

function streamResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) {
        controller.enqueue(encoder.encode(c));
      }
      controller.close();
    },
  });
  return new Response(stream, { status: 200 });
}

function baseRequest(): AiAnalyzeRequest {
  return {
    page: 'explore',
    promptType: 'summarize_trends',
    metadata: {
      dateRange: { start: '', end: '' },
      filteredTlds: [],
      filteredRegistrars: [],
    },
    chartData: { rows: [], columns: [] },
    conversationHistory: [],
  };
}

describe('AiAnalysisService', () => {
  let service: AiAnalysisService;
  let fetchSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AiAnalysisService);
    fetchSpy = spyOn(window, 'fetch');
  });

  it('hasActiveConversation is false initially', () => {
    expect(service.hasActiveConversation()).toBeFalse();
    expect(service.conversationHistory()).toEqual([]);
  });

  it('analyze appends assistant turn on success', async () => {
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"text","text":"hello"}\n\n',
        'data: {"type":"text","text":" world"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];
    await service.analyze(req);

    expect(service.error()).toBeNull();
    const history = service.conversationHistory();
    expect(history.length).toBe(2);
    expect(history[0]).toEqual({ role: 'user', content: 'hi' });
    expect(history[1].role).toBe('assistant');
    expect(history[1].content).toBe('hello world');
    expect(service.hasActiveConversation()).toBeTrue();
    expect(service.lastRequest()?.page).toBe('explore');
  });

  it('analyze does not append assistant turn on error', async () => {
    fetchSpy.and.resolveTo(new Response('boom', { status: 500 }));
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];
    await service.analyze(req);

    expect(service.error()).toBeTruthy();
    // user turn captured at the start; no assistant turn appended
    expect(service.conversationHistory().length).toBe(1);
    expect(service.lastRequest()).toBeNull();
  });

  it('appendUserTurnAndAnalyze appends one user turn and one assistant turn on success', async () => {
    // Seed one prior round.
    fetchSpy.and.resolveTo(
      streamResponse(['data: {"type":"text","text":"first"}\n\n', 'data: [DONE]\n\n']),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q1' }];
    await service.analyze(req);
    expect(service.conversationHistory().length).toBe(2);

    // Now follow up via appendUserTurnAndAnalyze.
    fetchSpy.and.resolveTo(
      streamResponse(['data: {"type":"text","text":"second"}\n\n', 'data: [DONE]\n\n']),
    );
    await service.appendUserTurnAndAnalyze('follow up', {
      chartData: { rows: [], columns: [] },
    });

    const history = service.conversationHistory();
    expect(history.length).toBe(4);
    expect(history[2]).toEqual({ role: 'user', content: 'follow up' });
    expect(history[3].role).toBe('assistant');
    expect(history[3].content).toBe('second');
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it('appendUserTurnAndAnalyze sets error when no prior request and no overrides', async () => {
    await service.appendUserTurnAndAnalyze('hi', { chartData: {} });
    expect(service.error()).toBeTruthy();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('cancel aborts the in-flight stream and clears streaming', async () => {
    // Build a stream that will be aborted mid-flight.
    let abortFn: (() => void) | null = null;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const enc = new TextEncoder();
        controller.enqueue(enc.encode('data: {"type":"text","text":"partial"}\n\n'));
        // Hold the stream open; abort signal will close it via fetch.
        abortFn = () => controller.close();
      },
    });
    fetchSpy.and.callFake((_url: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal;
      signal?.addEventListener('abort', () => abortFn?.());
      return Promise.resolve(new Response(stream, { status: 200 }));
    });

    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];

    const analyzePromise = service.analyze(req);
    // Yield so analyze() captures the user turn into history.
    await Promise.resolve();
    await Promise.resolve();

    const abortController = (service as unknown as { abortController: AbortController })
      .abortController;
    const abortSpy = spyOn(abortController, 'abort').and.callThrough();

    service.cancel();

    expect(abortSpy).toHaveBeenCalled();
    await analyzePromise;
    expect(service.streaming()).toBeFalse();
  });

  it('cancel does not mutate conversationHistory (preserves user turn)', async () => {
    let abortFn: (() => void) | null = null;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const enc = new TextEncoder();
        controller.enqueue(enc.encode('data: {"type":"text","text":"partial"}\n\n'));
        abortFn = () => controller.close();
      },
    });
    fetchSpy.and.callFake((_url: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal;
      signal?.addEventListener('abort', () => abortFn?.());
      return Promise.resolve(new Response(stream, { status: 200 }));
    });

    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];

    const analyzePromise = service.analyze(req);
    await Promise.resolve();
    await Promise.resolve();

    expect(service.conversationHistory().length).toBe(1);
    service.cancel();
    await analyzePromise;

    const history = service.conversationHistory();
    expect(history.length).toBe(1);
    expect(history[0]).toEqual({ role: 'user', content: 'hi' });
  });

  it('cancel sets error to null', async () => {
    let abortFn: (() => void) | null = null;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const enc = new TextEncoder();
        controller.enqueue(enc.encode('data: {"type":"text","text":"partial"}\n\n'));
        abortFn = () => controller.close();
      },
    });
    fetchSpy.and.callFake((_url: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal;
      signal?.addEventListener('abort', () => abortFn?.());
      return Promise.resolve(new Response(stream, { status: 200 }));
    });

    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];

    const analyzePromise = service.analyze(req);
    await Promise.resolve();
    await Promise.resolve();

    // Pre-set an error to verify cancel clears it.
    service.error.set('something');
    service.cancel();
    expect(service.error()).toBeNull();
    await analyzePromise;
    expect(service.error()).toBeNull();
  });

  it('resetConversation clears state', async () => {
    fetchSpy.and.resolveTo(
      streamResponse(['data: {"type":"text","text":"x"}\n\n', 'data: [DONE]\n\n']),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];
    await service.analyze(req);
    expect(service.hasActiveConversation()).toBeTrue();

    service.resetConversation();
    expect(service.hasActiveConversation()).toBeFalse();
    expect(service.conversationHistory()).toEqual([]);
    expect(service.lastRequest()).toBeNull();
    expect(service.streamedText()).toBe('');
    expect(service.error()).toBeNull();
    expect(service.toolsInFlight()).toEqual([]);
    expect(service.toolsUsed()).toEqual([]);
  });
});
