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
import { AiAnalyzeRequest, ToolMessage } from './ai-analysis.models';

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
    expect((history[1] as { content: string }).content).toBe('hello world');
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
    expect((history[3] as { content: string }).content).toBe('second');
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it('appendUserTurnAndAnalyze sets error when no prior request and no overrides', async () => {
    await service.appendUserTurnAndAnalyze('hi', { chartData: {} });
    expect(service.error()).toBeTruthy();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('tool_result EMPTY_FOR_RANGE persists tool entry in conversationHistory with diagnostic (SRE-1963)', async () => {
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"tool_use","tool":"query_transfers","args":{}}\n\n',
        'data: {"type":"tool_result","tool":"query_transfers","ok":true,"status":"EMPTY_FOR_RANGE","diagnostic":"no rows for tld=tld between 2026-01-01 and 2026-01-31"}\n\n',
        'data: {"type":"text","text":"done"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    await service.analyze(req);

    const history = service.conversationHistory();
    const toolEntries = history.filter((e): e is ToolMessage => e.role === 'tool');
    expect(toolEntries.length).toBe(1);
    expect(toolEntries[0].tool).toBe('query_transfers');
    expect(toolEntries[0].status).toBe('EMPTY_FOR_RANGE');
    expect(toolEntries[0].ok).toBeTrue();
    expect(toolEntries[0].diagnostic).toContain('no rows for tld=tld');
  });

  it('tool_result INVALID_ARGS persists non-ok tool entry in conversationHistory (SRE-1963)', async () => {
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"tool_use","tool":"query_revenue_breakdown","args":{}}\n\n',
        'data: {"type":"tool_result","tool":"query_revenue_breakdown","ok":false,"status":"INVALID_ARGS","diagnostic":"Missing required arg: tld"}\n\n',
        'data: {"type":"text","text":"sorry"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    await service.analyze(req);

    const history = service.conversationHistory();
    const toolEntries = history.filter((e): e is ToolMessage => e.role === 'tool');
    expect(toolEntries.length).toBe(1);
    expect(toolEntries[0].tool).toBe('query_revenue_breakdown');
    expect(toolEntries[0].status).toBe('INVALID_ARGS');
    expect(toolEntries[0].ok).toBeFalse();
    expect(toolEntries[0].diagnostic).toContain('Missing required arg');
  });

  it('multiple sequential tool calls each get their own entry, ordered (SRE-1963)', async () => {
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"tool_use","tool":"query_transfers","args":{}}\n\n',
        'data: {"type":"tool_result","tool":"query_transfers","ok":true,"status":"OK"}\n\n',
        'data: {"type":"tool_use","tool":"get_pricing_rules","args":{}}\n\n',
        'data: {"type":"tool_result","tool":"get_pricing_rules","ok":true,"status":"EMPTY_FOR_RANGE","diagnostic":"no rules"}\n\n',
        'data: {"type":"text","text":"done"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    await service.analyze(req);

    const tools = service.conversationHistory().filter(
      (e): e is ToolMessage => e.role === 'tool',
    );
    expect(tools.length).toBe(2);
    expect(tools[0].tool).toBe('query_transfers');
    expect(tools[0].status).toBe('OK');
    expect(tools[1].tool).toBe('get_pricing_rules');
    expect(tools[1].status).toBe('EMPTY_FOR_RANGE');
  });

  it('same tool invoked twice: results match pills FIFO (oldest first) (SRE-1963)', async () => {
    // Backend emits tool_result frames in the same FIFO order as tool_use.
    // If we matched LIFO, A1's result would land on the second pill and
    // A2's result on the first — diagnostics swapped.
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"tool_use","tool":"query_transfers","args":{"tld":"a"}}\n\n',
        'data: {"type":"tool_use","tool":"query_transfers","args":{"tld":"b"}}\n\n',
        'data: {"type":"tool_result","tool":"query_transfers","ok":true,"status":"OK","diagnostic":"first"}\n\n',
        'data: {"type":"tool_result","tool":"query_transfers","ok":true,"status":"EMPTY_FOR_RANGE","diagnostic":"second"}\n\n',
        'data: {"type":"text","text":"done"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    await service.analyze(req);

    const tools = service.conversationHistory().filter(
      (e): e is ToolMessage => e.role === 'tool',
    );
    expect(tools.length).toBe(2);
    // First pill (oldest IN_FLIGHT) should carry the FIRST result.
    expect(tools[0].status).toBe('OK');
    expect(tools[0].diagnostic).toBe('first');
    // Second pill should carry the second result.
    expect(tools[1].status).toBe('EMPTY_FOR_RANGE');
    expect(tools[1].diagnostic).toBe('second');
  });

  it('text → tool → text preserves chronological order in conversationHistory (SRE-1963)', async () => {
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"text","text":"thinking..."}\n\n',
        'data: {"type":"tool_use","tool":"query_transfers","args":{}}\n\n',
        'data: {"type":"tool_result","tool":"query_transfers","ok":true,"status":"OK"}\n\n',
        'data: {"type":"text","text":"all done"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    await service.analyze(req);

    const history = service.conversationHistory();
    // Expect: user → assistant("thinking...") → tool → assistant("all done")
    expect(history.length).toBe(4);
    expect(history[0].role).toBe('user');
    expect(history[1].role).toBe('assistant');
    expect((history[1] as { content: string }).content).toBe('thinking...');
    expect(history[2].role).toBe('tool');
    expect(history[3].role).toBe('assistant');
    expect((history[3] as { content: string }).content).toBe('all done');
  });

  it('IN_FLIGHT tool entry on stream-end-without-result gets "Interrupted" diagnostic (SRE-1963)', async () => {
    // Stream emits a tool_use, then closes cleanly with no tool_result —
    // simulates a network drop / unexpected stream end. Since the
    // controller was NOT aborted, the diagnostic should be "Interrupted",
    // not "Cancelled".
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"tool_use","tool":"query_transfers","args":{}}\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    await service.analyze(req);

    const tools = service.conversationHistory().filter(
      (e): e is ToolMessage => e.role === 'tool',
    );
    expect(tools.length).toBe(1);
    expect(tools[0].status).toBe('INTERNAL_ERROR');
    expect(tools[0].diagnostic).toBe('Interrupted');
  });

  it('IN_FLIGHT tool entry resolves to terminal state on cancel (SRE-1963)', async () => {
    let abortFn: (() => void) | null = null;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const enc = new TextEncoder();
        controller.enqueue(
          enc.encode('data: {"type":"tool_use","tool":"query_transfers","args":{}}\n\n'),
        );
        abortFn = () => controller.close();
      },
    });
    fetchSpy.and.callFake((_url: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal;
      signal?.addEventListener('abort', () => abortFn?.());
      return Promise.resolve(new Response(stream, { status: 200 }));
    });

    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q' }];
    const analyzePromise = service.analyze(req);

    // Yield until the tool_use frame has been processed (IN_FLIGHT
    // entry visible in history). Don't cap iterations too low — fetch
    // resolution + reader.read() each take a microtask plus internal
    // promise-chain churn that varies by platform.
    for (let i = 0; i < 50; i++) {
      await new Promise(r => setTimeout(r, 0));
      const toolsNow = service.conversationHistory().filter(
        (e): e is ToolMessage => e.role === 'tool',
      );
      if (toolsNow.length > 0) break;
    }

    service.cancel();
    await analyzePromise;

    const tools = service.conversationHistory().filter(
      (e): e is ToolMessage => e.role === 'tool',
    );
    expect(tools.length).toBe(1);
    expect(tools[0].status).not.toBe('IN_FLIGHT');
    expect(tools[0].status).toBe('INTERNAL_ERROR');
    expect(tools[0].diagnostic).toBe('Cancelled');
  });

  it('appendUserTurnAndAnalyze preserves tool entries from prior turns (SRE-1963)', async () => {
    // First turn: text + tool + text.
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"tool_use","tool":"query_transfers","args":{}}\n\n',
        'data: {"type":"tool_result","tool":"query_transfers","ok":true,"status":"OK"}\n\n',
        'data: {"type":"text","text":"first reply"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'q1' }];
    await service.analyze(req);

    // Second turn: just text.
    fetchSpy.and.resolveTo(
      streamResponse([
        'data: {"type":"text","text":"second reply"}\n\n',
        'data: [DONE]\n\n',
      ]),
    );
    await service.appendUserTurnAndAnalyze('q2', {
      chartData: { rows: [], columns: [] },
    });

    // Tool entry from turn 1 must survive into turn 2's timeline.
    const history = service.conversationHistory();
    const tools = history.filter((e): e is ToolMessage => e.role === 'tool');
    expect(tools.length).toBe(1);
    expect(tools[0].tool).toBe('query_transfers');

    // Wire-shape sent to backend on turn 2 must NOT contain the tool entry.
    const body = JSON.parse(fetchSpy.calls.mostRecent().args[1].body);
    const wireRoles = (body.conversationHistory as Array<{ role: string }>).map(m => m.role);
    expect(wireRoles).not.toContain('tool');
    expect(wireRoles).toEqual(['user', 'assistant', 'user']);
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

  it('clearError sets error to null', () => {
    service.error.set('something went wrong');
    expect(service.error()).toBe('something went wrong');
    service.clearError();
    expect(service.error()).toBeNull();
  });

  it('analyze forwards request body with dateRange omitted when caller did not set it', async () => {
    fetchSpy.and.resolveTo(
      streamResponse(['data: {"type":"text","text":"x"}\n\n', 'data: [DONE]\n\n']),
    );
    const req = baseRequest();
    req.conversationHistory = [{ role: 'user', content: 'hi' }];
    await service.analyze(req);

    expect(fetchSpy).toHaveBeenCalled();
    const body = JSON.parse(fetchSpy.calls.mostRecent().args[1].body);
    expect(body.metadata.dateRange).toBeUndefined();
  });

  it('analyze forwards a populated dateRange unchanged when caller provides one', async () => {
    fetchSpy.and.resolveTo(
      streamResponse(['data: {"type":"text","text":"x"}\n\n', 'data: [DONE]\n\n']),
    );
    const req = baseRequest();
    req.metadata = {
      ...req.metadata,
      dateRange: { start: '2025-05-04', end: '2026-05-04' },
    };
    req.conversationHistory = [{ role: 'user', content: 'hi' }];
    await service.analyze(req);

    const body = JSON.parse(fetchSpy.calls.mostRecent().args[1].body);
    expect(body.metadata.dateRange).toEqual({ start: '2025-05-04', end: '2026-05-04' });
  });

  it('appendUserTurnAndAnalyze fallback metadata omits dateRange', async () => {
    fetchSpy.and.resolveTo(
      streamResponse(['data: {"type":"text","text":"first"}\n\n', 'data: [DONE]\n\n']),
    );
    await service.appendUserTurnAndAnalyze('q', {
      page: 'explore',
      promptType: 'summarize_trends',
      chartData: {},
    });
    const body = JSON.parse(fetchSpy.calls.mostRecent().args[1].body);
    expect(body.metadata.dateRange).toBeUndefined();
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
  });
});
