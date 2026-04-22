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

import { Component, OnInit, DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { combineLatest, EMPTY, switchMap, catchError } from 'rxjs';
import { MaterialModule } from '../../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../../ud-echarts';
import { RegistryDashService, RANGE_CONFIG, computeDateRange } from '../../registry-dash.service';
import { DrillDownService } from '../../drilldown/drilldown.service';
import { ExploreService } from '../../explore/explore.service';
import { withDrillDown } from '../../ud-echarts';
import { LongPressDirective } from '../../drilldown/long-press.directive';

const OPERATION_COLORS: Record<string, string> = {
  CREATE: '#0D67FE',
  RENEW: '#0546B7',
  TRANSFER: '#65A1DA',
  RESTORE: '#192B55',
};

const TLD_COLORS = [
  '#0D67FE', '#0546B7', '#65A1DA', '#192B55',
  '#00C9FF', '#0A5FEA', '#4A9B30', '#9191A1',
];

@Component({
  selector: 'app-revenue-billing',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective, LongPressDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './revenue-billing.component.html',
  styleUrls: ['./revenue-billing.component.scss'],
})
export class RevenueBillingComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  lastHoveredRevenueByTld: any = null;
  lastHoveredRevenueByOp: any = null;

  data = computed(() => this.dashService.revenueBilling());

  /** Total net amount received by the registry over the selected period. */
  totalNetAmountToRegistry = computed(() => this.data()?.totals.totalNetAmountToRegistry ?? 0);

  /** Average net-to-registry per time bucket over the selected period. */
  avgPerBucketRegistryRevenue = computed(() => {
    const d = this.data();
    if (!d || d.periodRevenue.length === 0) return 0;
    const periods = new Set(d.periodRevenue.map(p => p.period));
    return periods.size > 0 ? (d.totals.totalNetAmountToRegistry / periods.size) : 0;
  });

  /** Top TLD by Net to Registry over the selected period. */
  topTld = computed(() => {
    const d = this.data();
    if (!d || d.periodRevenue.length === 0) return '—';
    const byTld = new Map<string, number>();
    for (const pt of d.periodRevenue) {
      byTld.set(pt.tld, (byTld.get(pt.tld) ?? 0) + pt.netAmountToRegistry);
    }
    let best = '';
    let bestVal = 0;
    for (const [tld, val] of byTld) {
      if (val > bestVal) { best = tld; bestVal = val; }
    }
    return best || '—';
  });

  currency = computed(() => this.data()?.totals.currency ?? 'USD');

  /** Registry Revenue by TLD — one stacked area series per TLD using Net to Registry. */
  revenueLineOptions = computed(() => {
    const d = this.data();
    if (!d || d.periodRevenue.length === 0) return null;

    const tldMap = new Map<string, Map<string, number>>();
    const allPeriods = new Set<string>();
    for (const pt of d.periodRevenue) {
      allPeriods.add(pt.period);
      if (!tldMap.has(pt.tld)) tldMap.set(pt.tld, new Map());
      const periodMap = tldMap.get(pt.tld)!;
      periodMap.set(pt.period, (periodMap.get(pt.period) ?? 0) + pt.netAmountToRegistry);
    }

    const periods = [...allPeriods].sort();
    const tlds = [...tldMap.keys()].sort();
    const tldLabels = tlds.map(t => `.${t}`);
    const series = tlds.map((tld, i) => {
      const periodMap = tldMap.get(tld)!;
      return withDrillDown({
        name: `.${tld}`,
        type: 'line' as const,
        stack: 'revenue',
        areaStyle: { opacity: 0.15 },
        emphasis: { focus: 'series' as const },
        data: periods.map(m => periodMap.get(m) ?? 0),
        color: TLD_COLORS[i % TLD_COLORS.length],
      });
    });

    return {
      tooltip: { trigger: 'axis' as const },
      legend: { type: 'scroll' as const, bottom: 0, data: tldLabels },
      xAxis: { type: 'category' as const, data: periods },
      yAxis: { type: 'value' as const, axisLabel: { formatter: '${value}' } },
      dataZoom: [{ type: 'inside' as const, start: 0, end: 100 }],
      series,
    };
  });

  /** Registry Revenue by Operation — using Net to Registry per operation. */
  operationBarOptions = computed(() => {
    const d = this.data();
    if (!d) return null;
    const byOp = d.totals.byOperationNetAmountToRegistry;
    const operations = Object.keys(byOp);
    if (operations.length === 0) return null;

    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'value' as const, axisLabel: { formatter: '${value}' } },
      yAxis: { type: 'category' as const, data: operations, inverse: true },
      series: [
        withDrillDown({
          type: 'bar' as const,
          data: operations.map(op => ({
            value: byOp[op],
            itemStyle: { color: OPERATION_COLORS[op] || '#9191A1' },
          })),
        }),
      ],
    };
  });

  constructor(
    public dashService: RegistryDashService,
    private drillDown: DrillDownService,
    private exploreService: ExploreService,
  ) {
    combineLatest([
      toObservable(this.dashService.selectedTimeRange),
      toObservable(this.dashService.selectedTlds),
      toObservable(this.dashService.selectedRegistrarIds),
    ]).pipe(
      switchMap(([range, tlds, regIds]) => {
        const config = RANGE_CONFIG[range];
        const ft = tlds.length > 0 ? tlds : undefined;
        const fr = regIds.length > 0 ? regIds : undefined;
        return this.dashService.getRevenueBilling(
          config.lookbackHours, config.granularity, ft, fr
        ).pipe(catchError(() => EMPTY));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }

  ngOnInit() {
    // Initial fetch is handled by the observable above
  }

  onRevenueByTldClick(event: any) {
    if (event.seriesName) this.drillDown.applyTldFilter(event.seriesName);
  }

  onRevenueByTldContext(event: any) {
    event.event?.event?.preventDefault();
    if (event.seriesName) this.drillDown.drillDownRevenueByTld(event.seriesName);
  }

  onRevenueByOpContext(event: any) {
    event.event?.event?.preventDefault();
    if (event.name) this.drillDown.drillDownRevenueByOperation(event.name);
  }

  onRevenueByTldLongPress() {
    if (this.lastHoveredRevenueByTld?.seriesName) {
      this.drillDown.drillDownRevenueByTld(this.lastHoveredRevenueByTld.seriesName);
    }
  }

  onRevenueByOpLongPress() {
    if (this.lastHoveredRevenueByOp?.name) {
      this.drillDown.drillDownRevenueByOperation(this.lastHoveredRevenueByOp.name);
    }
  }

  private buildFilters(): { tlds?: string[]; registrarIds?: string[] } {
    const tlds = this.dashService.selectedTlds();
    const regIds = this.dashService.selectedRegistrarIds();
    return {
      ...(tlds.length > 0 ? { tlds: [...tlds] } : {}),
      ...(regIds.length > 0 ? { registrarIds: [...regIds] } : {}),
    };
  }

  exploreRevenueByTld() {
    const config = this.dashService.selectedRangeConfig();
    this.exploreService.navigateToExplore({
      dataSource: 'REVENUE',
      metrics: [{ field: 'netAmountToRegistry', aggregation: 'sum' }],
      dimensions: ['period', 'tld'],
      granularity: config.granularity,
      filters: {
        ...this.buildFilters(),
        dateRange: computeDateRange(config.lookbackHours),
      },
    }, 'area');
  }

  exploreRevenueByOperation() {
    const config = this.dashService.selectedRangeConfig();
    this.exploreService.navigateToExplore({
      dataSource: 'REVENUE',
      metrics: [{ field: 'netAmountToRegistry', aggregation: 'sum' }],
      dimensions: ['operation'],
      granularity: config.granularity,
      filters: {
        ...this.buildFilters(),
        dateRange: computeDateRange(config.lookbackHours),
      },
    }, 'horizontal-bar');
  }
}
