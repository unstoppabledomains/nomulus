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

import { signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import {
  AI_MODAL_HEIGHT_KEY,
  AI_MODAL_WIDTH_KEY,
  AiAnalysisModalComponent,
  AiAnalysisModalData,
  aiModalConfig,
} from './ai-analysis-modal.component';
import { AiAnalysisService } from './ai-analysis.service';
import { AiModalResizeDirective } from './ai-modal-resize.directive';
import { ConversationMessage, ToolInFlight } from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';

/**
 * Stub of AiAnalysisService that exposes the same Signal API as the real
 * service, plus a controllable `analyze()` whose lifecycle (streaming
 * flip, history append, error) the tests drive directly. This lets the
 * auto-fire effect run end-to-end against realistic state transitions
 * instead of mocking the SUT itself.
 */
class StubAiService {
  streaming = signal(false);
  streamedText = signal('');
  error = signal<string | null>(null);
  toolsInFlight = signal<ToolInFlight[]>([]);
  toolsUsed = signal<string[]>([]);
  conversationHistory = signal<ConversationMessage[]>([]);
  lastRequest = signal<unknown>(null);
  hasActiveConversation = signal(false);

  // Records each analyze() invocation in order so tests can assert FIFO.
  analyzeCalls: Array<{ history: ConversationMessage[] }> = [];
  // Pending resolver for the current in-flight analyze(); call
  // `completeStream(text)` to flip streaming → false and append the
  // assistant turn (mimicking the real service's success path).
  private currentResolve: (() => void) | null = null;

  clearStaleDisplayState(): void {
    if (this.streaming()) return;
    this.streamedText.set('');
    this.error.set(null);
    this.toolsInFlight.set([]);
    this.toolsUsed.set([]);
  }

  clearError(): void {
    this.error.set(null);
  }

  resetConversation(): void {
    this.conversationHistory.set([]);
    this.hasActiveConversation.set(false);
    this.streaming.set(false);
    this.error.set(null);
    this.streamedText.set('');
    this.lastRequest.set(null);
    this.currentResolve = null;
  }

  cancel(): void {
    this.error.set(null);
    if (this.currentResolve) {
      this.streaming.set(false);
      const r = this.currentResolve;
      this.currentResolve = null;
      r();
    }
  }

  analyze(req: { conversationHistory: ConversationMessage[] }): Promise<void> {
    this.analyzeCalls.push({ history: req.conversationHistory.slice() });
    this.streaming.set(true);
    this.streamedText.set('');
    this.error.set(null);
    this.conversationHistory.set([...req.conversationHistory]);
    this.hasActiveConversation.set(true);
    return new Promise<void>(resolve => {
      this.currentResolve = resolve;
    });
  }

  /**
   * Test helper: complete the in-flight analyze() successfully, appending
   * an assistant turn and flipping streaming → false (matches real
   * service ordering: history updated BEFORE streaming flip).
   */
  completeStream(assistantText = 'ok'): void {
    if (!this.currentResolve) return;
    this.conversationHistory.update(h => [
      ...h,
      { role: 'assistant', content: assistantText },
    ]);
    this.streaming.set(false);
    const r = this.currentResolve;
    this.currentResolve = null;
    r();
  }

  /**
   * Test helper: fail the in-flight analyze() with an error. Matches the
   * real service: `error` is set, history NOT appended, streaming → false.
   */
  failStream(message = 'boom'): void {
    if (!this.currentResolve) return;
    this.error.set(message);
    this.streaming.set(false);
    const r = this.currentResolve;
    this.currentResolve = null;
    r();
  }
}

const baseData: AiAnalysisModalData = {
  title: 'Test AI',
  page: 'explore',
  promptType: 'summarize_trends',
  userMessage: 'initial message',
  metadata: {
    dateRange: { start: '', end: '' },
    filteredTlds: [],
    filteredRegistrars: [],
  },
  chartData: { rows: [], columns: [] },
  isAdmin: false,
};

describe('AiAnalysisModalComponent', () => {
  let component: AiAnalysisModalComponent;
  let fixture: ComponentFixture<AiAnalysisModalComponent>;
  let stubService: StubAiService;
  let dialogRefSpy: jasmine.SpyObj<MatDialogRef<AiAnalysisModalComponent>>;

  function makeFixture(data: AiAnalysisModalData = baseData) {
    stubService = new StubAiService();
    // Mark hasActiveConversation true so ngOnInit doesn't fire the
    // initial request — tests want to drive analyze() explicitly.
    stubService.conversationHistory.set([
      { role: 'user', content: 'seed' },
      { role: 'assistant', content: 'seeded reply' },
    ]);
    stubService.hasActiveConversation.set(true);

    dialogRefSpy = jasmine.createSpyObj<MatDialogRef<AiAnalysisModalComponent>>(
      'MatDialogRef',
      ['close', 'updateSize'],
    );

    TestBed.configureTestingModule({
      imports: [AiAnalysisModalComponent, BrowserAnimationsModule],
      providers: [
        { provide: AiAnalysisService, useValue: stubService },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRefSpy },
        {
          provide: RegistryDashService,
          useValue: { updateSettingsSelf: () => of({}) },
        },
      ],
    });

    fixture = TestBed.createComponent(AiAnalysisModalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.removeItem(AI_MODAL_WIDTH_KEY);
    localStorage.removeItem(AI_MODAL_HEIGHT_KEY);
  });

  afterEach(() => {
    localStorage.removeItem(AI_MODAL_WIDTH_KEY);
    localStorage.removeItem(AI_MODAL_HEIGHT_KEY);
    TestBed.resetTestingModule();
  });

  // ──────────────────────────────────────────────────────────────────
  // Sub-feature 1: auto-scroll-to-follow
  // ──────────────────────────────────────────────────────────────────
  describe('auto-scroll behavior (sub-feature 1)', () => {
    beforeEach(() => makeFixture());

    function setScroll(top: number, height: number, client: number) {
      const el = component.scrollContainer.nativeElement;
      Object.defineProperty(el, 'scrollHeight', { configurable: true, value: height });
      Object.defineProperty(el, 'clientHeight', { configurable: true, value: client });
      // scrollTop is also stubbed because the test container has no
      // overflow content and the browser would otherwise clamp it to 0.
      let _top = top;
      Object.defineProperty(el, 'scrollTop', {
        configurable: true,
        get: () => _top,
        set: (v: number) => {
          _top = v;
        },
      });
    }

    it('scroll-to-bottom suppressed when user scrolled up >40px', () => {
      // height=1000, client=500, top=200 → 1000-200-500 = 300 > 40 → not at bottom
      setScroll(200, 1000, 500);
      component.scrollContainer.nativeElement.dispatchEvent(new Event('scroll'));
      expect(component.autoScrollEnabled()).toBeFalse();
    });

    it('autoScrollEnabled stays true when within 40px of bottom', () => {
      setScroll(490, 1000, 500); // 1000-490-500 = 10 ≤ 40 → at bottom
      component.scrollContainer.nativeElement.dispatchEvent(new Event('scroll'));
      expect(component.autoScrollEnabled()).toBeTrue();
    });

    it('showJumpToLatest is true while scrolled up during a stream', () => {
      stubService.streaming.set(true);
      setScroll(200, 1000, 500);
      component.scrollContainer.nativeElement.dispatchEvent(new Event('scroll'));
      expect(component.showJumpToLatest()).toBeTrue();
    });

    it('showJumpToLatest is false when not streaming, even if scrolled up', () => {
      stubService.streaming.set(false);
      setScroll(200, 1000, 500);
      component.scrollContainer.nativeElement.dispatchEvent(new Event('scroll'));
      expect(component.showJumpToLatest()).toBeFalse();
    });

    it('jumpToLatest re-enables auto-scroll and scrolls to bottom', () => {
      setScroll(0, 1000, 500);
      component.autoScrollEnabled.set(false);
      component.jumpToLatest();
      expect(component.autoScrollEnabled()).toBeTrue();
      expect(component.scrollContainer.nativeElement.scrollTop).toBe(1000);
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Sub-feature 2: type-while-responding
  // ──────────────────────────────────────────────────────────────────
  describe('type during response (sub-feature 2)', () => {
    beforeEach(() => makeFixture());

    it('textarea is NOT disabled while streaming', () => {
      stubService.streaming.set(true);
      fixture.detectChanges();
      const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('.follow-up-input');
      expect(textarea.disabled).toBeFalse();
    });

    function setInputViaDom(value: string) {
      const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('.follow-up-input');
      textarea.value = value;
      textarea.dispatchEvent(new Event('input'));
    }

    it('queue-hint visible only when streaming AND followUpText non-empty', () => {
      stubService.streaming.set(true);
      setInputViaDom('something');
      fixture.detectChanges();
      const hint = fixture.nativeElement.querySelector('.queue-hint');
      expect(hint).toBeTruthy();
      expect(hint.textContent).toContain('Will send after current response');
    });

    it('queue-hint hidden when not streaming', () => {
      stubService.streaming.set(false);
      setInputViaDom('something');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.queue-hint')).toBeNull();
    });

    it('queue-hint hidden when streaming but input empty', () => {
      stubService.streaming.set(true);
      setInputViaDom('   ');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.queue-hint')).toBeNull();
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Sub-feature 3: prompt queue
  // ──────────────────────────────────────────────────────────────────
  describe('prompt queue (sub-feature 3)', () => {
    beforeEach(() => makeFixture());

    it('enqueueOrSend while streaming pushes to queue (no analyze call)', () => {
      stubService.streaming.set(true);
      const before = stubService.analyzeCalls.length;
      component.followUpText = 'queued one';
      component.enqueueOrSend(component.followUpText);
      expect(stubService.analyzeCalls.length).toBe(before);
      expect(component.pendingQueue()).toEqual(['queued one']);
      expect(component.followUpText).toBe('');
    });

    it('enqueueOrSend while idle fires immediately (no queue add)', () => {
      stubService.streaming.set(false);
      component.followUpText = 'go';
      component.enqueueOrSend(component.followUpText);
      expect(component.pendingQueue().length).toBe(0);
      expect(stubService.analyzeCalls.length).toBe(1);
      expect(component.followUpText).toBe('');
    });

    it('three queued prompts fire in FIFO order after stream completion', fakeAsync(() => {
      // Start a stream.
      stubService.streaming.set(false);
      component.enqueueOrSend('first');
      flushMicrotasks();
      expect(stubService.analyzeCalls.length).toBe(1);
      expect(stubService.analyzeCalls[0].history.at(-1)?.content).toBe('first');

      // While 'first' is streaming, queue two more.
      component.enqueueOrSend('second');
      component.enqueueOrSend('third');
      expect(component.pendingQueue()).toEqual(['second', 'third']);

      // Complete first → effect should fire 'second'.
      stubService.completeStream('reply 1');
      tick();
      flushMicrotasks();
      expect(stubService.analyzeCalls.length).toBe(2);
      expect(stubService.analyzeCalls[1].history.at(-1)?.content).toBe('second');
      expect(component.pendingQueue()).toEqual(['third']);

      // Complete second → effect should fire 'third'.
      stubService.completeStream('reply 2');
      tick();
      flushMicrotasks();
      expect(stubService.analyzeCalls.length).toBe(3);
      expect(stubService.analyzeCalls[2].history.at(-1)?.content).toBe('third');
      expect(component.pendingQueue()).toEqual([]);

      // Complete third → no further fire.
      stubService.completeStream('reply 3');
      tick();
      flushMicrotasks();
      expect(stubService.analyzeCalls.length).toBe(3);
    }));

    it('pendingCount tracks queue length', () => {
      component.pendingQueue.set(['a', 'b', 'c']);
      expect(component.pendingCount()).toBe(3);
      component.pendingQueue.set([]);
      expect(component.pendingCount()).toBe(0);
    });

    it('Stop preserves queue and sets isPaused; auto-fire does not drain', fakeAsync(() => {
      // Kick off a stream.
      component.enqueueOrSend('one');
      flushMicrotasks();
      expect(stubService.streaming()).toBeTrue();

      // Queue more.
      component.enqueueOrSend('two');
      component.enqueueOrSend('three');
      expect(component.pendingQueue().length).toBe(2);

      // Stop.
      component.onStop();
      tick();
      flushMicrotasks();

      expect(component.isPaused()).toBeTrue();
      expect(component.pendingQueue()).toEqual(['two', 'three']);
      // Only the original 'one' invocation should have fired.
      expect(stubService.analyzeCalls.length).toBe(1);
    }));

    it('Resume button appears only after Stop; Resume clears isPaused and fires head', fakeAsync(() => {
      component.enqueueOrSend('one');
      flushMicrotasks();
      component.enqueueOrSend('two');
      component.onStop();
      tick();
      flushMicrotasks();
      fixture.detectChanges();

      // Resume button rendered.
      const resumeBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.queue-resume-btn');
      expect(resumeBtn).toBeTruthy();
      expect(resumeBtn.textContent?.trim()).toBe('Resume');

      const fired = stubService.analyzeCalls.length;
      component.resumeQueue();
      tick();
      flushMicrotasks();

      expect(component.isPaused()).toBeFalse();
      expect(stubService.analyzeCalls.length).toBe(fired + 1);
      expect(stubService.analyzeCalls.at(-1)?.history.at(-1)?.content).toBe('two');
      expect(component.pendingQueue()).toEqual([]);
    }));

    it('mid-stream error preserves queue; Retry clears error/paused and fires head', fakeAsync(() => {
      component.enqueueOrSend('one');
      flushMicrotasks();
      component.enqueueOrSend('two');
      component.enqueueOrSend('three');

      // Fail the in-flight stream.
      stubService.failStream('network blew up');
      tick();
      flushMicrotasks();

      // Queue preserved, error visible, no further fires.
      expect(component.error()).toBe('network blew up');
      expect(component.pendingQueue()).toEqual(['two', 'three']);
      expect(stubService.analyzeCalls.length).toBe(1);

      // Render Retry button.
      fixture.detectChanges();
      const retryBtn: HTMLButtonElement = fixture.nativeElement.querySelector('.queue-retry-btn');
      expect(retryBtn).toBeTruthy();

      // Click Retry: clears both isPaused and error → auto-fire drains.
      component.retryAfterError();
      tick();
      flushMicrotasks();

      expect(component.isPaused()).toBeFalse();
      expect(stubService.analyzeCalls.length).toBe(2);
      expect(stubService.analyzeCalls.at(-1)?.history.at(-1)?.content).toBe('two');
    }));

    it('editQueued replaces followUpText with queued text and removes the chip', () => {
      component.pendingQueue.set(['alpha', 'bravo', 'charlie']);
      component.followUpText = 'draft';
      component.editQueued(1);
      expect(component.followUpText).toBe('bravo');
      expect(component.pendingQueue()).toEqual(['alpha', 'charlie']);
    });

    it('editQueued is a no-op for out-of-range index', () => {
      component.pendingQueue.set(['x']);
      component.followUpText = 'draft';
      component.editQueued(5);
      expect(component.followUpText).toBe('draft');
      expect(component.pendingQueue()).toEqual(['x']);
    });

    it('removeQueued removes only the targeted chip', () => {
      component.pendingQueue.set(['a', 'b', 'c']);
      component.removeQueued(1);
      expect(component.pendingQueue()).toEqual(['a', 'c']);
    });

    it('startNewChat clears queue and isPaused', () => {
      component.pendingQueue.set(['a', 'b']);
      component.isPaused.set(true);
      component.startNewChat();
      expect(component.pendingQueue()).toEqual([]);
      expect(component.isPaused()).toBeFalse();
    });

    it('startNewChat invalidates a scheduled-but-not-yet-fired drain microtask', fakeAsync(() => {
      // Race scenario: the auto-fire effect synchronously pops a queue
      // head and schedules a microtask, then the user clicks Start New
      // Chat BEFORE the microtask runs. The stale microtask must NOT
      // fire the popped head into the new chat (which would also abort
      // the new chat's initial analyze() call).
      stubService.streaming.set(false);
      stubService.error.set(null);

      const callsBefore = stubService.analyzeCalls.length;
      // Push something into pendingQueue → effect runs synchronously,
      // pops head, schedules microtask. We deliberately do NOT flush
      // microtasks yet.
      component.pendingQueue.set(['stale-head']);
      // The effect uses a signal write (pendingQueue.set([])) which
      // schedules. Trigger effect resolution without flushing the
      // outer microtask. In Angular 19 signals, effects run in the
      // current zone — detectChanges resolves them. After this, the
      // queue should be empty (head popped) but the microtask still
      // pending.
      fixture.detectChanges();
      expect(component.pendingQueue()).toEqual([]);

      // Now invoke startNewChat BEFORE microtasks flush.
      component.startNewChat();
      // startNewChat → sendInitialRequest → analyze() (one call recorded).
      // The stale microtask should now be a no-op.
      flushMicrotasks();
      tick();
      flushMicrotasks();

      // We expect exactly ONE analyze call (the one from startNewChat's
      // sendInitialRequest), NOT two (which would happen if the stale
      // 'stale-head' microtask also fired).
      const newCalls = stubService.analyzeCalls.length - callsBefore;
      expect(newCalls).toBe(1);
      // And specifically the recorded call should NOT carry the stale head.
      const lastCall = stubService.analyzeCalls.at(-1);
      expect(lastCall?.history.at(-1)?.content).not.toBe('stale-head');
    }));

    it('renders one chip per queued prompt with truncation past 50 chars', () => {
      // Pause first so the auto-fire effect doesn't drain the queue
      // during this assertion (streaming defaults to false in the stub).
      component.isPaused.set(true);
      const longText = 'x'.repeat(80);
      component.pendingQueue.set(['short', longText]);
      fixture.detectChanges();
      // Verify state-level invariant: queue holds two entries.
      expect(component.pendingQueue().length).toBe(2);
      // The mat-chip element nests deeply in Material 19; assert on the
      // overall queue-row text plus the truncation behavior of the
      // template binding (independent of the host element name).
      const queueRow = fixture.nativeElement.querySelector('.queue-row');
      expect(queueRow).toBeTruthy();
      expect(queueRow.textContent).toContain('short');
      expect(queueRow.textContent).toContain('…');
      // Truncated text should NOT contain the full 80-char run.
      expect(queueRow.textContent).not.toContain('x'.repeat(60));
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Sub-feature 4: resize + persistence
  // ──────────────────────────────────────────────────────────────────
  describe('resize and persistence (sub-feature 4)', () => {
    beforeEach(() => makeFixture());

    it('aiModalConfig returns defaults when localStorage is empty', () => {
      const cfg = aiModalConfig({ ...baseData });
      expect(cfg.width).toBe('960px');
      expect(cfg.height).toBe('85vh');
      expect(cfg.maxWidth).toBe('95vw');
      expect(cfg.maxHeight).toBe('95vh');
    });

    it('onModalResizeCommit persists width and height; aiModalConfig reads them back', () => {
      component.onModalResizeCommit({ width: 720.4, height: 580.6 });
      expect(localStorage.getItem(AI_MODAL_WIDTH_KEY)).toBe('720');
      expect(localStorage.getItem(AI_MODAL_HEIGHT_KEY)).toBe('581');
      const cfg = aiModalConfig({ ...baseData });
      expect(cfg.width).toBe('720px');
      expect(cfg.height).toBe('581px');
    });

    it('aiModalConfig caps via maxWidth/maxHeight tokens (95vw/95vh)', () => {
      // Persist a huge value; aiModalConfig still reflects the saved
      // pixel value but maxWidth/maxHeight tokens cap it at render.
      localStorage.setItem(AI_MODAL_WIDTH_KEY, '99999');
      localStorage.setItem(AI_MODAL_HEIGHT_KEY, '99999');
      const cfg = aiModalConfig({ ...baseData });
      expect(cfg.width).toBe('99999px');
      expect(cfg.maxWidth).toBe('95vw');
      expect(cfg.maxHeight).toBe('95vh');
    });

    it('aiModalConfig rejects sub-minimum and non-finite saved values', () => {
      localStorage.setItem(AI_MODAL_WIDTH_KEY, '100'); // < 480 min
      localStorage.setItem(AI_MODAL_HEIGHT_KEY, 'NaN');
      const cfg = aiModalConfig({ ...baseData });
      expect(cfg.width).toBe('960px');
      expect(cfg.height).toBe('85vh');
    });

    it('AiModalResizeDirective minimum constants match plan (480 x 400)', () => {
      expect(AiModalResizeDirective.MIN_WIDTH).toBe(480);
      expect(AiModalResizeDirective.MIN_HEIGHT).toBe(400);
    });

    it('onModalResize forwards rounded size to dialogRef.updateSize', () => {
      component.onModalResize({ width: 800.7, height: 600.2 });
      expect(dialogRefSpy.updateSize).toHaveBeenCalledWith('801px', '600px');
    });
  });

  // ──────────────────────────────────────────────────────────────────
  // Sub-feature 5: input UX
  // ──────────────────────────────────────────────────────────────────
  describe('input UX (sub-feature 5)', () => {
    beforeEach(() => makeFixture());

    it('plain Enter calls enqueueOrSend; Shift+Enter does not', () => {
      const spy = spyOn(component, 'enqueueOrSend').and.callThrough();
      component.followUpText = 'hello';
      const enter = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: false });
      spyOn(enter, 'preventDefault');
      component.onFollowUpKeydown(enter);
      expect(spy).toHaveBeenCalledWith('hello');
      expect(enter.preventDefault).toHaveBeenCalled();

      spy.calls.reset();
      const shiftEnter = new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true });
      component.onFollowUpKeydown(shiftEnter);
      expect(spy).not.toHaveBeenCalled();
    });

    it('send button is disabled when followUpText is empty/whitespace and not streaming', () => {
      stubService.streaming.set(false);
      const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('.follow-up-input');
      const setVal = (v: string) => {
        textarea.value = v;
        textarea.dispatchEvent(new Event('input'));
        fixture.detectChanges();
      };
      setVal('');
      const btn: HTMLButtonElement = fixture.nativeElement.querySelector(
        '.follow-up-bar button',
      );
      expect(btn.disabled).toBeTrue();

      setVal('   ');
      expect(btn.disabled).toBeTrue();

      setVal('go');
      expect(btn.disabled).toBeFalse();
    });

    it('icon swaps send → stop while streaming and tooltip swaps too', () => {
      stubService.streaming.set(false);
      fixture.detectChanges();
      const btn: HTMLButtonElement = fixture.nativeElement.querySelector('.follow-up-bar button');
      expect(btn.querySelector('mat-icon')?.textContent?.trim()).toBe('send');

      stubService.streaming.set(true);
      fixture.detectChanges();
      expect(btn.querySelector('mat-icon')?.textContent?.trim()).toBe('stop');
    });

    it('stop variant always enabled while streaming, regardless of input emptiness', () => {
      stubService.streaming.set(true);
      component.followUpText = '';
      fixture.detectChanges();
      const btn: HTMLButtonElement = fixture.nativeElement.querySelector('.follow-up-bar button');
      expect(btn.disabled).toBeFalse();
    });

    it('textarea has cdkAutosizeMaxRows="6" attribute', () => {
      const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('.follow-up-input');
      expect(textarea.getAttribute('cdkAutosizeMaxRows')).toBe('6');
    });

    it('textarea has cdkAutosizeMinRows="1" attribute', () => {
      const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('.follow-up-input');
      expect(textarea.getAttribute('cdkAutosizeMinRows')).toBe('1');
    });
  });
});
