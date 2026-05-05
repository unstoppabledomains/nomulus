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

import { Component, Input, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { FinancialsComponent } from './financials.component';
import { RegistryDashService, RANGE_CONFIG } from '../registry-dash.service';
import { DrillDownService } from '../drilldown/drilldown.service';
import { NgxEchartsDirective } from 'ngx-echarts';
import { LongPressDirective } from '../drilldown/long-press.directive';
import {
  REVENUE_BILLING_PROMPTS,
  PRICING_PROMPTS,
  ASK_ANYTHING_PROMPT,
} from '../ai/ai-prompts';
import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';
import { RevenueBillingComponent } from './revenue-billing/revenue-billing.component';
import { ForecastingComponent } from './forecasting/forecasting.component';
import { EffectiveFeesComponent } from './effective-fees/effective-fees.component';
import { FilterPanelComponent } from '../filter-panel/filter-panel.component';

@Component({ selector: 'app-ai-sparkle-button', standalone: true, template: '' })
class StubAiSparkleButtonComponent {
  @Input() page: any;
  @Input() prompts: any;
  @Input() chartData: any;
  @Input() isAdmin: any;
}

@Component({ selector: 'app-revenue-billing', standalone: true, template: '' })
class StubRevenueBillingComponent {}
@Component({ selector: 'app-forecasting', standalone: true, template: '' })
class StubForecastingComponent {}
@Component({ selector: 'app-effective-fees', standalone: true, template: '' })
class StubEffectiveFeesComponent {}
@Component({ selector: 'app-filter-panel', standalone: true, template: '' })
class StubFilterPanelComponent {
  @Input() showTimeRange: any;
}

describe('FinancialsComponent', () => {
  let fixture: ComponentFixture<FinancialsComponent>;
  let component: FinancialsComponent;
  let dashServiceStub: any;
  let drillDownStub: jasmine.SpyObj<DrillDownService>;

  const sampleRevenueBilling = {
    periodRevenue: [],
    totals: {
      totalRevenue: 1000,
      totalNetAmountToRegistry: 700,
      currency: 'USD',
      byOperation: { CREATE: 800, RENEW: 200 },
      byOperationNetAmountToRegistry: { CREATE: 560, RENEW: 140 },
    },
  };

  const sampleTldFees = [
    { tld: 'app', operation: 'CREATE', defaultPrice: 10, currency: 'USD' },
    { tld: 'app', operation: 'RENEW', defaultPrice: 8, currency: 'USD' },
    { tld: 'dev', operation: 'CREATE', defaultPrice: 12, currency: 'USD' },
  ];

  beforeEach(async () => {
    dashServiceStub = {
      loading: signal(false),
      error: signal<string | undefined>(undefined),
      revenueBilling: signal<typeof sampleRevenueBilling | undefined>(sampleRevenueBilling),
      domainActivity: signal<any>({ activity: [] }),
      forecasting: signal<any>({ renewalRates: [] }),
      filteredTldFees: signal(sampleTldFees),
      selectedTimeRange: signal('12m'),
      selectedTlds: signal<string[]>([]),
      selectedRegistrarIds: signal<string[]>([]),
      selectedRangeConfig: computed(() => RANGE_CONFIG['12m']),
      timeRangeLabel: computed(() => 'last 12 months'),
      getDomainActivity: jasmine.createSpy('getDomainActivity').and.returnValue(of({})),
      getForecasting: jasmine.createSpy('getForecasting').and.returnValue(of({})),
      getRevenueBilling: jasmine.createSpy('getRevenueBilling').and.returnValue(of(sampleRevenueBilling)),
      getTldFees: jasmine.createSpy('getTldFees').and.returnValue(of(sampleTldFees)),
    };
    drillDownStub = jasmine.createSpyObj('DrillDownService', [
      'drillDownRevenueByOperation',
      'applyTldFilter',
      'drillDownFeesByTld',
    ]);

    await TestBed.configureTestingModule({
      imports: [FinancialsComponent, NoopAnimationsModule],
      providers: [
        { provide: RegistryDashService, useValue: dashServiceStub },
        { provide: DrillDownService, useValue: drillDownStub },
      ],
    })
      .overrideComponent(FinancialsComponent, {
        remove: {
          imports: [
            NgxEchartsDirective,
            LongPressDirective,
            RevenueBillingComponent,
            ForecastingComponent,
            EffectiveFeesComponent,
            FilterPanelComponent,
            AiSparkleButtonComponent,
          ],
          providers: [],
        },
        add: {
          imports: [
            StubRevenueBillingComponent,
            StubForecastingComponent,
            StubEffectiveFeesComponent,
            StubFilterPanelComponent,
            StubAiSparkleButtonComponent,
          ],
        },
      })
      .compileComponents();

    fixture = TestBed.createComponent(FinancialsComponent);
    component = fixture.componentInstance;
  });

  it('creates', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  describe('AI prompt menus', () => {
    it('exposes REVENUE_BILLING_PROMPTS for the Overview chart ✨', () => {
      expect(component.aiPromptsRevenue).toBe(REVENUE_BILLING_PROMPTS);
    });

    it('exposes PRICING_PROMPTS for the Default Fees by TLD ✨', () => {
      expect(component.aiPromptsPricing).toBe(PRICING_PROMPTS);
    });

    it('both prompt menus end with ASK_ANYTHING_PROMPT (cold-start always last)', () => {
      expect(component.aiPromptsRevenue[component.aiPromptsRevenue.length - 1])
        .toBe(ASK_ANYTHING_PROMPT);
      expect(component.aiPromptsPricing[component.aiPromptsPricing.length - 1])
        .toBe(ASK_ANYTHING_PROMPT);
    });
  });

  describe('chart-context payloads', () => {
    it('overviewRevenueAiContext mirrors the revenue-billing data', () => {
      fixture.detectChanges();
      expect(component.overviewRevenueAiContext()).toBe(sampleRevenueBilling);
    });

    it('feesByTldAiContext bundles both the per-TLD bar data and raw fee entries', () => {
      fixture.detectChanges();
      const ctx = component.feesByTldAiContext();
      expect(ctx.feesByTld.length).toBe(2); // 'app' and 'dev'
      expect(ctx.tldFeeEntries).toBe(sampleTldFees);
    });
  });

  // DOM rendering of the new ✨ buttons inside Material tabs is verified via
  // manual `--chrome` smoke testing on alpha — mat-tab-group lazy-renders tab
  // bodies, which is awkward to drive deterministically in a unit test. The
  // signal-level tests above prove the chart-context payloads, page types,
  // and prompts are wired correctly; effective-fees.component.spec covers a
  // full DOM-render assertion since that component has no tab gating.
});
