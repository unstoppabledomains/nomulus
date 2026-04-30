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
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { signal, computed, Component, Input } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ExploreComponent } from './explore.component';
import { ExploreService } from './explore.service';
import { ExploreResult } from './explore.models';
import { RegistryDashService } from '../registry-dash.service';
import { AiAnalysisService } from '../ai/ai-analysis.service';
import { ExploreBuilderComponent } from './explore-builder/explore-builder.component';
import { ExploreChartComponent } from './explore-chart/explore-chart.component';
import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';

@Component({ selector: 'app-explore-builder', standalone: true, template: '' })
class StubExploreBuilderComponent {
  @Input() query: any;
  @Input() chartType: any;
}

@Component({ selector: 'app-explore-chart', standalone: true, template: '' })
class StubExploreChartComponent {
  @Input() result: any;
  @Input() chartType: any;
  @Input() dimensions: any;
  @Input() metricColumn: any;
}

@Component({ selector: 'app-ai-sparkle-button', standalone: true, template: '' })
class StubAiSparkleButtonComponent {
  @Input() page: any;
  @Input() prompts: any;
  @Input() chartData: any;
  @Input() isAdmin: any;
}

describe('ExploreComponent', () => {
  let fixture: ComponentFixture<ExploreComponent>;
  let component: ExploreComponent;
  let exploreServiceStub: any;
  let dashServiceStub: any;
  let aiServiceStub: any;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  const sampleResult: ExploreResult = {
    columns: ['tld', 'count_sum'],
    rows: [
      { tld: 'modem', count_sum: 100 },
      { tld: 'nft', count_sum: 50 },
    ],
    truncated: false,
    totalRows: 2,
  };

  beforeEach(async () => {
    exploreServiceStub = {
      result: signal<ExploreResult | undefined>(undefined),
      loading: signal(false),
      error: signal<string | undefined>(undefined),
      pendingQuery: signal(null),
      explore: jasmine.createSpy('explore').and.returnValue(of(sampleResult)),
    };
    dashServiceStub = {
      hasActiveFilters: () => false,
      selectedTlds: signal<string[]>([]),
      selectedRegistrarIds: signal<string[]>([]),
      selectedTimeRange: signal('7d'),
      selectedRangeConfig: computed(() => ({ lookbackHours: 168, granularity: 'day' })),
      settingsCache: signal<Record<string, any>>({}),
    };
    aiServiceStub = {
      conversationHistory: signal<any[]>([]),
      hasActiveConversation: signal(false),
      resetConversation: jasmine.createSpy('resetConversation'),
      appendUserTurnAndAnalyze: jasmine.createSpy('appendUserTurnAndAnalyze')
        .and.returnValue(Promise.resolve()),
    };
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

    await TestBed.configureTestingModule({
      imports: [ExploreComponent, NoopAnimationsModule],
      providers: [
        { provide: ExploreService, useValue: exploreServiceStub },
        { provide: RegistryDashService, useValue: dashServiceStub },
        { provide: AiAnalysisService, useValue: aiServiceStub },
        { provide: MatDialog, useValue: dialogSpy },
      ],
    })
      .overrideComponent(ExploreComponent, {
        remove: {
          imports: [
            ExploreBuilderComponent,
            ExploreChartComponent,
            AiSparkleButtonComponent,
          ],
        },
        add: {
          imports: [
            StubExploreBuilderComponent,
            StubExploreChartComponent,
            StubAiSparkleButtonComponent,
          ],
        },
      })
      .compileComponents();

    fixture = TestBed.createComponent(ExploreComponent);
    component = fixture.componentInstance;
  });

  function findAddToAiBtn(): HTMLButtonElement | null {
    const buttons: NodeListOf<HTMLButtonElement> =
      fixture.nativeElement.querySelectorAll('button');
    return Array.from(buttons).find(b =>
      b.textContent?.includes('Add to AI Chat'),
    ) as HTMLButtonElement | null;
  }

  it('Add to AI Chat button is disabled when there is no result', () => {
    fixture.detectChanges();
    const btn = findAddToAiBtn();
    expect(btn).not.toBeNull();
    expect(btn!.disabled).toBeTrue();
  });

  it('Add to AI Chat button is enabled when result is set', () => {
    exploreServiceStub.result.set(sampleResult);
    fixture.detectChanges();
    const btn = findAddToAiBtn();
    expect(btn).not.toBeNull();
    expect(btn!.disabled).toBeFalse();
  });

  it('addToNewChat resets the conversation and opens the dialog with exploreDescriptor metadata', () => {
    exploreServiceStub.result.set(sampleResult);
    fixture.detectChanges();
    component.addToNewChat();
    expect(aiServiceStub.resetConversation).toHaveBeenCalled();
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.page).toBe('explore');
    expect(data.metadata.exploreDescriptor).toBeTruthy();
    expect(data.metadata.exploreDescriptor.dataSource).toBe('DOMAIN_ACTIVITY');
    expect(data.chartData.rows.length).toBe(2);
  });

  it('addToCurrentChat is a no-op when there is no active conversation', () => {
    exploreServiceStub.result.set(sampleResult);
    aiServiceStub.hasActiveConversation.set(false);
    fixture.detectChanges();
    component.addToCurrentChat();
    expect(aiServiceStub.appendUserTurnAndAnalyze).not.toHaveBeenCalled();
    expect(dialogSpy.open).not.toHaveBeenCalled();
  });

  it('addToCurrentChat calls appendUserTurnAndAnalyze and opens the dialog when a chat is active', () => {
    exploreServiceStub.result.set(sampleResult);
    aiServiceStub.hasActiveConversation.set(true);
    fixture.detectChanges();
    component.addToCurrentChat();
    expect(aiServiceStub.appendUserTurnAndAnalyze).toHaveBeenCalledTimes(1);
    const args = aiServiceStub.appendUserTurnAndAnalyze.calls.first().args;
    const userTurn = args[0] as string;
    const overrides = args[1] as any;
    expect(userTurn).toContain('I just ran an Explore query');
    expect(userTurn).toContain('Descriptor:');
    expect(overrides.page).toBe('explore');
    expect(overrides.metadata.exploreDescriptor).toBeTruthy();
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
  });

  it('truncatedChartData includes rows, returnedRows, totalRows, and truncated=false when under the cap', () => {
    exploreServiceStub.result.set(sampleResult);
    const t = component.truncatedChartData();
    expect(t).not.toBeNull();
    expect(t!.rows.length).toBe(2);
    expect(t!.returnedRows).toBe(2);
    expect(t!.totalRows).toBe(2);
    expect(t!.truncated).toBeFalse();
  });

  it('truncatedChartData truncates and reports truncated=true when row count exceeds the cap', () => {
    const bigRows = Array.from({ length: 250 }, (_, i) => ({ tld: `tld${i}`, count_sum: i }));
    exploreServiceStub.result.set({
      columns: ['tld', 'count_sum'],
      rows: bigRows,
      truncated: false,
      totalRows: 250,
    });
    const t = component.truncatedChartData();
    expect(t!.rows.length).toBe(100);
    expect(t!.returnedRows).toBe(100);
    expect(t!.truncated).toBeTrue();
  });
});
