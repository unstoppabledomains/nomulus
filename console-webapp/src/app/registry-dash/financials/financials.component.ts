// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { Component, OnInit, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { combineLatest, EMPTY, switchMap, catchError } from 'rxjs';
import { MaterialModule } from '../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../ud-echarts';
import { RegistryDashService, RANGE_CONFIG } from '../registry-dash.service';
import { RevenueBillingComponent } from './revenue-billing/revenue-billing.component';
import { DrillDownService } from '../drilldown/drilldown.service';
import { withDrillDown } from '../ud-echarts';
import { LongPressDirective } from '../drilldown/long-press.directive';
import { ForecastingComponent } from './forecasting/forecasting.component';
import { EffectiveFeesComponent } from './effective-fees/effective-fees.component';
import { FilterPanelComponent } from '../filter-panel/filter-panel.component';
import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';
import { REVENUE_BILLING_PROMPTS, PRICING_PROMPTS } from '../ai/ai-prompts';

const OPERATION_COLORS: Record<string, string> = {
  CREATE: '#0D67FE',
  RENEW: '#0546B7',
  TRANSFER: '#65A1DA',
  RESTORE: '#192B55',
};

@Component({
  selector: 'app-registry-dash-financials',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective, RevenueBillingComponent, ForecastingComponent, EffectiveFeesComponent, LongPressDirective, FilterPanelComponent, AiSparkleButtonComponent],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './financials.component.html',
  styleUrls: ['./financials.component.scss'],
})
export class FinancialsComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  lastHoveredOverviewRevenueByOp: any = null;
  selectedTab = signal(0);

  /** Prompt menus surfaced on this page's ✨ buttons. Reused from the dedicated
   * revenue-billing and pricing pages so we don't fan out the backend prompt
   * menu for Financials sub-areas (see SRE-1957 plan). */
  aiPromptsRevenue = REVENUE_BILLING_PROMPTS;
  aiPromptsPricing = PRICING_PROMPTS;

  timeRelevantTab = computed(() => {
    const tab = this.selectedTab();
    return tab === 0 || tab === 3 || tab === 4;
  });

  overviewRangeLabel = computed(() => this.dashService.timeRangeLabel());

  // --- Fees by TLD tab ---

  tldFeeEntries = computed(() => this.dashService.filteredTldFees());

  feesTableColumns = ['tld', 'operation', 'defaultPrice', 'currency'];

  /** Chart-context payload sent to the LLM when ✨ is clicked on the Default
   * Fees by TLD chart/table. Includes both the per-TLD aggregated bar data and
   * the raw fee entries so the model can reason about either view. */
  feesByTldAiContext = computed(() => ({
    feesByTld: this.feesByOperationChartData(),
    tldFeeEntries: this.tldFeeEntries(),
  }));

  /** Chart: Default fees per TLD per operation. Segments = operations, bar = sum of defaultPrice. */
  feesByOperationChartData = computed(() => {
    const entries = this.tldFeeEntries();
    if (entries.length === 0) return [];
    const grouped = new Map<string, Map<string, number>>();
    for (const e of entries) {
      if (!grouped.has(e.tld)) grouped.set(e.tld, new Map());
      grouped.get(e.tld)!.set(e.operation, e.defaultPrice ?? 0);
    }
    const tlds = [...grouped.keys()].sort();
    const totals = tlds.map(tld => [...(grouped.get(tld)?.values() ?? [])].reduce((s, v) => s + v, 0));
    const maxTotal = Math.max(...totals, 1);
    return tlds.map((tld, i) => {
      const opMap = grouped.get(tld)!;
      const segments = [...opMap.entries()].map(([op, amount]) => ({
        operation: op, amount,
        color: OPERATION_COLORS[op] || '#9191A1',
      }));
      return {
        tld, total: totals[i],
        totalWidthPercent: (totals[i] / maxTotal) * 100,
        segments,
      };
    });
  });

  // --- Overview tab ---

  /** Registry revenue (Net to Registry) over selected period. */
  overviewTotalRevenue = computed(() => this.dashService.revenueBilling()?.totals.totalNetAmountToRegistry ?? 0);
  overviewCurrency = computed(() => this.dashService.revenueBilling()?.totals.currency ?? 'USD');

  /** Net domain growth (creates minus deletes) over selected period. */
  overviewNetGrowth = computed(() => {
    const d = this.dashService.domainActivity();
    if (!d) return 0;
    const creates = d.activity.filter(pt => pt.type === 'CREATES').reduce((s, pt) => s + pt.count, 0);
    const deletes = d.activity.filter(pt => pt.type === 'DELETES').reduce((s, pt) => s + pt.count, 0);
    return creates - deletes;
  });

  /** Average renewal rate across all TLDs over selected period. */
  overviewAvgRenewalRate = computed(() => {
    const d = this.dashService.forecasting();
    if (!d || d.renewalRates.length === 0) return 0;
    return d.renewalRates.reduce((s, r) => s + r.renewalRate, 0) / d.renewalRates.length;
  });

  /** Chart-context payload for the Overview "Registry Revenue by Operation"
   * ✨ button. Mirrors what the Registry Revenue tab passes (the whole
   * revenue-billing dataset) so the LLM has the same context regardless of
   * which entry point the user clicked from. */
  overviewRevenueAiContext = computed(() => this.dashService.revenueBilling());

  /** Registry revenue by operation (Net to Registry per operation). */
  overviewRevenueByOpOptions = computed(() => {
    const d = this.dashService.revenueBilling();
    if (!d) return null;
    const byOp = d.totals.byOperationNetAmountToRegistry;
    const operations = Object.keys(byOp);
    if (operations.length === 0) return null;
    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'category' as const, data: operations },
      yAxis: { type: 'value' as const, axisLabel: { formatter: '${value}' } },
      series: [withDrillDown({
        type: 'bar' as const,
        data: operations.map(op => ({
          value: byOp[op],
          itemStyle: { color: OPERATION_COLORS[op] || '#9191A1' },
        })),
      })],
    };
  });

  constructor(
    public dashService: RegistryDashService,
    private drillDown: DrillDownService,
  ) {
    combineLatest([
      toObservable(this.selectedTab),
      toObservable(this.dashService.selectedTimeRange),
      toObservable(this.dashService.selectedTlds),
      toObservable(this.dashService.selectedRegistrarIds),
    ]).pipe(
      switchMap(([tab, range, tlds, regIds]) => {
        const config = RANGE_CONFIG[range];
        const ft = tlds.length > 0 ? tlds : undefined;
        const fr = regIds.length > 0 ? regIds : undefined;
        if (tab === 0) {
          // Fetch all three in parallel for overview tab
          this.dashService.getDomainActivity(config.lookbackHours, config.granularity, ft, fr).subscribe();
          this.dashService.getForecasting(config.lookbackHours, config.granularity, ft, fr).subscribe();
          return this.dashService.getRevenueBilling(
            config.lookbackHours, config.granularity, ft, fr
          ).pipe(catchError(() => EMPTY));
        }
        return [];
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }

  ngOnInit() {
    this.dashService.getTldFees().subscribe();
  }

  onTabChange(index: number) {
    this.selectedTab.set(index);
  }

  getOperationColor(operation: string): string {
    return OPERATION_COLORS[operation] || '#9191A1';
  }

  onOverviewRevenueByOpContext(event: any) {
    event.event?.event?.preventDefault();
    if (event.name) this.drillDown.drillDownRevenueByOperation(event.name);
  }

  onFeesByTldClick(tld: string) {
    this.drillDown.applyTldFilter(tld);
  }

  onFeesByTldContext(event: MouseEvent, tld: string) {
    event.preventDefault();
    this.drillDown.drillDownFeesByTld(tld);
  }

  onOverviewRevenueByOpLongPress() {
    if (this.lastHoveredOverviewRevenueByOp?.name) {
      this.drillDown.drillDownRevenueByOperation(this.lastHoveredOverviewRevenueByOp.name);
    }
  }
}
