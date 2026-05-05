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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import {
  AiAnalysisModalComponent,
  AiAnalysisModalData,
} from './ai-analysis-modal.component';
import { AiAnalysisService } from './ai-analysis.service';
import { ConversationMessage } from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';

function makeData(overrides: Partial<AiAnalysisModalData> = {}): AiAnalysisModalData {
  return {
    title: 'Summarize trends — Test Page',
    page: 'revenue-billing',
    promptType: 'summarize_trends',
    userMessage: 'Summarize trends.',
    metadata: {
      dateRange: { start: '2026-01-01', end: '2026-02-01' },
      filteredTlds: ['app'],
      filteredRegistrars: ['reg-a'],
    },
    chartData: { rows: [{ a: 1 }] },
    isAdmin: false,
    ...overrides,
  };
}

interface MockAiService {
  conversationHistory: ReturnType<typeof signal<ConversationMessage[]>>;
  streaming: ReturnType<typeof signal<boolean>>;
  streamedText: ReturnType<typeof signal<string>>;
  error: ReturnType<typeof signal<string | null>>;
  toolsInFlight: ReturnType<typeof signal<any[]>>;
  hasActiveConversation: ReturnType<typeof signal<boolean>>;
  analyze: jasmine.Spy;
  clearStaleDisplayState: jasmine.Spy;
  resetConversation: jasmine.Spy;
}

function createMockAiService(): MockAiService {
  return {
    conversationHistory: signal<ConversationMessage[]>([]),
    streaming: signal(false),
    streamedText: signal(''),
    error: signal<string | null>(null),
    toolsInFlight: signal<any[]>([]),
    hasActiveConversation: signal(false),
    analyze: jasmine.createSpy('analyze').and.resolveTo(undefined),
    clearStaleDisplayState: jasmine.createSpy('clearStaleDisplayState'),
    resetConversation: jasmine.createSpy('resetConversation'),
  };
}

async function setup(
  data: AiAnalysisModalData,
  mockService: MockAiService,
): Promise<ComponentFixture<AiAnalysisModalComponent>> {
  const dashSpy = jasmine.createSpyObj('RegistryDashService', [
    'updateSettingsSelf',
    'getAiModelCatalog',
  ]);
  dashSpy.updateSettingsSelf.and.returnValue(of(undefined));
  // Master added a getAiModelCatalog() call in modal ngOnInit (PR #132,
  // SRE-1959). Stub a populated catalog so modal init doesn't fall back
  // to the empty-families branch.
  dashSpy.getAiModelCatalog.and.returnValue(of({
    catalog: { haiku: [{ id: 'haiku-x' }], sonnet: [{ id: 'sonnet-x' }], opus: [{ id: 'opus-x' }] },
    fetchedAt: '2026-01-01T00:00:00Z',
  }));

  await TestBed.configureTestingModule({
    imports: [AiAnalysisModalComponent, NoopAnimationsModule],
    providers: [
      { provide: AiAnalysisService, useValue: mockService },
      { provide: RegistryDashService, useValue: dashSpy },
      { provide: MatDialogRef, useValue: { close: () => undefined } },
      { provide: MAT_DIALOG_DATA, useValue: data },
    ],
  }).compileComponents();

  return TestBed.createComponent(AiAnalysisModalComponent);
}

describe('AiAnalysisModalComponent', () => {
  let mockService: MockAiService;

  beforeEach(() => {
    mockService = createMockAiService();
  });

  describe('ngOnInit', () => {
    it('does NOT auto-fire analyze when userMessage is empty (cold-start)', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      fixture.detectChanges();
      expect(mockService.analyze).not.toHaveBeenCalled();
    });

    it('DOES auto-fire analyze with the seeded user turn when userMessage is non-empty', async () => {
      const fixture = await setup(makeData({ userMessage: 'Summarize trends.' }), mockService);
      fixture.detectChanges();
      expect(mockService.analyze).toHaveBeenCalledTimes(1);
      const arg = mockService.analyze.calls.mostRecent().args[0];
      expect(arg.conversationHistory).toEqual([
        { role: 'user', content: 'Summarize trends.' },
      ]);
      expect(arg.page).toBe('revenue-billing');
      expect(arg.promptType).toBe('summarize_trends');
      expect(arg.chartData).toEqual({ rows: [{ a: 1 }] });
    });

    it('does NOT auto-fire when hasActiveConversation is true (continued-session guard)', async () => {
      mockService.hasActiveConversation.set(true);
      const fixture = await setup(makeData({ userMessage: 'seed' }), mockService);
      fixture.detectChanges();
      expect(mockService.analyze).not.toHaveBeenCalled();
    });

    it('always calls clearStaleDisplayState (SRE-1954 guard)', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      fixture.detectChanges();
      expect(mockService.clearStaleDisplayState).toHaveBeenCalledTimes(1);
    });
  });

  describe('isColdStart', () => {
    it('is true when conversation history is empty and not streaming', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      fixture.detectChanges();
      expect(fixture.componentInstance.isColdStart()).toBeTrue();
    });

    it('is false when streaming is true', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      mockService.streaming.set(true);
      fixture.detectChanges();
      expect(fixture.componentInstance.isColdStart()).toBeFalse();
    });

    it('is false once conversation history is non-empty', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      mockService.conversationHistory.set([{ role: 'user', content: 'hi' }]);
      fixture.detectChanges();
      expect(fixture.componentInstance.isColdStart()).toBeFalse();
    });
  });

  describe('sendFollowUp (also serves as cold-start first-turn submitter)', () => {
    it('dispatches analyze with a single-message history when conversation is empty', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      fixture.detectChanges(); // ngOnInit — should not auto-fire
      mockService.analyze.calls.reset();

      const component = fixture.componentInstance;
      component.followUpText = 'What changed last week?';
      await component.sendFollowUp();

      expect(mockService.analyze).toHaveBeenCalledTimes(1);
      const arg = mockService.analyze.calls.mostRecent().args[0];
      expect(arg.conversationHistory).toEqual([
        { role: 'user', content: 'What changed last week?' },
      ]);
      expect(arg.metadata).toEqual(makeData().metadata);
      expect(arg.chartData).toEqual({ rows: [{ a: 1 }] });
      expect(component.followUpText).toBe('');
    });

    it('appends to existing conversation history on follow-up turns', async () => {
      const fixture = await setup(makeData({ userMessage: 'seed' }), mockService);
      mockService.conversationHistory.set([
        { role: 'user', content: 'seed' },
        { role: 'assistant', content: 'first reply' },
      ]);
      mockService.hasActiveConversation.set(true);
      fixture.detectChanges();
      mockService.analyze.calls.reset();

      const component = fixture.componentInstance;
      component.followUpText = 'tell me more';
      await component.sendFollowUp();

      expect(mockService.analyze).toHaveBeenCalledTimes(1);
      const arg = mockService.analyze.calls.mostRecent().args[0];
      expect(arg.conversationHistory).toEqual([
        { role: 'user', content: 'seed' },
        { role: 'assistant', content: 'first reply' },
        { role: 'user', content: 'tell me more' },
      ]);
    });

    it('is a no-op when followUpText is whitespace-only', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      fixture.detectChanges();
      mockService.analyze.calls.reset();

      fixture.componentInstance.followUpText = '   ';
      await fixture.componentInstance.sendFollowUp();
      expect(mockService.analyze).not.toHaveBeenCalled();
    });

    it('is a no-op while streaming is true', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      mockService.streaming.set(true);
      fixture.detectChanges();
      mockService.analyze.calls.reset();

      fixture.componentInstance.followUpText = 'hi';
      await fixture.componentInstance.sendFollowUp();
      expect(mockService.analyze).not.toHaveBeenCalled();
    });
  });

  describe('startNewChat', () => {
    it('resets and re-fires the seeded request for preset entries', async () => {
      const fixture = await setup(makeData({ userMessage: 'seed' }), mockService);
      fixture.detectChanges();
      mockService.analyze.calls.reset();
      mockService.resetConversation.calls.reset();

      fixture.componentInstance.startNewChat();
      expect(mockService.resetConversation).toHaveBeenCalledTimes(1);
      expect(mockService.analyze).toHaveBeenCalledTimes(1);
    });

    it('resets without auto-firing for cold-start entries (empty userMessage)', async () => {
      const fixture = await setup(makeData({ userMessage: '' }), mockService);
      fixture.detectChanges();
      mockService.analyze.calls.reset();
      mockService.resetConversation.calls.reset();

      fixture.componentInstance.startNewChat();
      expect(mockService.resetConversation).toHaveBeenCalledTimes(1);
      expect(mockService.analyze).not.toHaveBeenCalled();
    });
  });
});
