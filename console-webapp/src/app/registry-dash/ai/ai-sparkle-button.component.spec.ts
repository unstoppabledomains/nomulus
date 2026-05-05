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
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { AiSparkleButtonComponent } from './ai-sparkle-button.component';
import {
  ASK_ANYTHING_PROMPT,
  REVENUE_BILLING_PROMPTS,
} from './ai-prompts';
import { AiAnalysisService } from './ai-analysis.service';
import { AiAnalysisModalData } from './ai-analysis-modal.component';
import { RegistryDashService, RANGE_CONFIG } from '../registry-dash.service';
import { UserDataService } from '../../shared/services/userData.service';

function lastDialogData(spy: jasmine.SpyObj<MatDialog>): AiAnalysisModalData {
  const config = spy.open.calls.mostRecent().args[1] as { data: AiAnalysisModalData };
  return config.data;
}

describe('AiSparkleButtonComponent', () => {
  let fixture: ComponentFixture<AiSparkleButtonComponent>;
  let component: AiSparkleButtonComponent;
  let mockDialog: jasmine.SpyObj<MatDialog>;
  let mockAiService: jasmine.SpyObj<AiAnalysisService>;
  let mockDashService: any;
  let mockUserDataService: any;

  beforeEach(async () => {
    mockDialog = jasmine.createSpyObj('MatDialog', ['open']);
    mockAiService = jasmine.createSpyObj('AiAnalysisService', ['resetConversation']);
    mockDashService = {
      selectedRangeConfig: signal(RANGE_CONFIG['12m']),
      selectedTlds: signal<string[]>(['app', 'dev']),
      selectedRegistrarIds: signal<string[]>(['reg-a']),
      settingsCache: signal<Record<string, any> | undefined>({ aiModel: 'sonnet' }),
    };
    mockUserDataService = {
      userData: signal<{ isAdmin: boolean } | null>({ isAdmin: false }),
    };

    await TestBed.configureTestingModule({
      imports: [AiSparkleButtonComponent, NoopAnimationsModule],
      providers: [
        { provide: MatDialog, useValue: mockDialog },
        { provide: AiAnalysisService, useValue: mockAiService },
        { provide: RegistryDashService, useValue: mockDashService },
        { provide: UserDataService, useValue: mockUserDataService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AiSparkleButtonComponent);
    component = fixture.componentInstance;
    // The component injects MatDialog via its constructor. The MatDialogModule
    // (transitively imported via MaterialModule) registers MatDialog at the
    // component-level injector, which shadows the TestBed-level useValue
    // provider. Stamp the spy directly on the instance to defeat that.
    (component as any).dialog = mockDialog;
    component.page = 'revenue-billing';
    component.prompts = REVENUE_BILLING_PROMPTS;
    component.chartData = { rows: [{ x: 1 }] };
    fixture.detectChanges();
  });

  it('renders the sparkle trigger button', () => {
    const button = fixture.nativeElement.querySelector('button.sparkle-button');
    expect(button).toBeTruthy();
    expect(button.querySelector('mat-icon')?.textContent?.trim()).toBe('auto_awesome');
  });

  it('REVENUE_BILLING_PROMPTS includes ASK_ANYTHING_PROMPT as its last entry', () => {
    // Sanity check — drives the rendered menu shape below.
    expect(component.prompts[component.prompts.length - 1]).toBe(ASK_ANYTHING_PROMPT);
  });

  describe('onPromptSelect', () => {
    it('resets the conversation before opening the dialog (SRE-1954 guard)', () => {
      const callOrder: string[] = [];
      mockAiService.resetConversation.and.callFake(() => callOrder.push('reset'));
      mockDialog.open.and.callFake(() => {
        callOrder.push('open');
        return {} as any;
      });

      component.onPromptSelect(REVENUE_BILLING_PROMPTS[0]);

      expect(callOrder).toEqual(['reset', 'open']);
    });

    it('opens the modal with the seeded userMessage for a preset entry', () => {
      mockDialog.open.and.returnValue({} as any);
      const preset = REVENUE_BILLING_PROMPTS[0];
      component.onPromptSelect(preset);

      expect(mockDialog.open).toHaveBeenCalledTimes(1);
      const data = lastDialogData(mockDialog);
      expect(data.userMessage).toBe(preset.userMessage);
      expect(data.promptType).toBe(preset.promptType);
    });

    it('opens the modal with empty userMessage and ask_anything promptType for the cold-start entry', () => {
      mockDialog.open.and.returnValue({} as any);
      component.onPromptSelect(ASK_ANYTHING_PROMPT);

      expect(mockDialog.open).toHaveBeenCalledTimes(1);
      const data = lastDialogData(mockDialog);
      expect(data.userMessage).toBe('');
      expect(data.promptType).toBe('ask_anything');
    });

    it('forwards the same chart-context payload (metadata + chartData) for preset and ask-anything entries', () => {
      mockDialog.open.and.returnValue({} as any);
      const preset = REVENUE_BILLING_PROMPTS[0];

      component.onPromptSelect(preset);
      const presetData = lastDialogData(mockDialog);

      mockDialog.open.calls.reset();
      component.onPromptSelect(ASK_ANYTHING_PROMPT);
      const askData = lastDialogData(mockDialog);

      expect(askData.metadata.filteredTlds).toEqual(presetData.metadata.filteredTlds);
      expect(askData.metadata.filteredRegistrars).toEqual(presetData.metadata.filteredRegistrars);
      expect(askData.metadata.granularity).toBe(presetData.metadata.granularity);
      expect(askData.chartData).toEqual(presetData.chartData);
      expect(askData.page).toBe(presetData.page);
    });

    it('passes the current dashboard filters as metadata', () => {
      mockDialog.open.and.returnValue({} as any);
      component.onPromptSelect(ASK_ANYTHING_PROMPT);
      const data = lastDialogData(mockDialog);
      expect(data.metadata.filteredTlds).toEqual(['app', 'dev']);
      expect(data.metadata.filteredRegistrars).toEqual(['reg-a']);
    });
  });
});
