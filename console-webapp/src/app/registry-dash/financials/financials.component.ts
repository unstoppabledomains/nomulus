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
import { RegistryDashService } from '../registry-dash.service';
import { RevenueBillingComponent, RANGE_CONFIG } from './revenue-billing/revenue-billing.component';
import { DrillDownService } from '../drilldown/drilldown.service';
import { withDrillDown } from '../ud-echarts';
import { ForecastingComponent } from './forecasting/forecasting.component';
import { EffectiveFeesComponent } from './effective-fees/effective-fees.component';

const OPERATION_COLORS: Record<string, string> = {
  CREATE: '#0D67FE',
  RENEW: '#0546B7',
  TRANSFER: '#65A1DA',
  RESTORE: '#192B55',
};

@Component({
  selector: 'app-registry-dash-financials',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective, RevenueBillingComponent, ForecastingComponent, EffectiveFeesComponent],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './financials.component.html',
  styleUrls: ['./financials.component.scss'],
})
export class FinancialsComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  selectedTab = signal(0);
  selectedOverviewRange = signal('12m');
  overviewRangeKeys = Object.keys(RANGE_CONFIG);

  private static readonly RANGE_LABELS: Record<string, string> = {
    '6h': '6 Hours', '12h': '12 Hours', '1d': '1 Day', '7d': '7 Days',
    '30d': '30 Days', '3m': '3 Months', '6m': '6 Months',
    '12m': '12 Months', '24m': '24 Months',
  };

  overviewRangeLabel = computed(() =>
    FinancialsComponent.RANGE_LABELS[this.selectedOverviewRange()] ?? this.selectedOverviewRange()
  );

  // --- Fees by TLD tab ---

  tldFeeEntries = computed(() => this.dashService.filteredTldFees());

  feesTableColumns = ['tld', 'operation', 'defaultPrice', 'currency'];

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
    // Re-fetch all overview data when range, tab, or global filters change
    combineLatest([
      toObservable(this.selectedTab),
      toObservable(this.selectedOverviewRange),
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

  onOverviewRangeChange(range: string) {
    this.selectedOverviewRange.set(range);
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
}
