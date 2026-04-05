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

import { Component, OnInit, computed, effect, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MaterialModule } from '../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../ud-echarts';
import { RegistryDashService } from '../registry-dash.service';
import { RevenueBillingComponent, RANGE_CONFIG } from './revenue-billing/revenue-billing.component';
import { ForecastingComponent } from './forecasting/forecasting.component';

const OPERATION_COLORS: Record<string, string> = {
  CREATE: '#0D67FE',
  RENEW: '#0546B7',
  TRANSFER: '#65A1DA',
  RESTORE: '#192B55',
};

const ENTITY_COLORS = {
  rspFee: '#4A9B30',
  netToRegistry: '#192B55',
};

@Component({
  selector: 'app-registry-dash-financials',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective, RevenueBillingComponent, ForecastingComponent],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './financials.component.html',
  styleUrls: ['./financials.component.scss'],
})
export class FinancialsComponent implements OnInit {
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

  costBasisEntries = computed(() => this.dashService.costBasis());

  private static readonly FEES_TABLE_BASE_COLUMNS = ['tld', 'operation', 'registrarPays'];
  private static readonly FEES_TABLE_GATED: Record<string, string> = {
    rspFee: 'financials.feesRspPays',
    netToRegistry: 'financials.feesNetToRegistry',
  };
  private static readonly FEES_TABLE_TAIL_COLUMNS = ['currency', 'effectiveDate'];

  feesTableColumns = computed(() => {
    const cols = [...FinancialsComponent.FEES_TABLE_BASE_COLUMNS];
    for (const [col, key] of Object.entries(FinancialsComponent.FEES_TABLE_GATED)) {
      if (this.dashService.isColumnVisible(key)) cols.push(col);
    }
    return [...cols, ...FinancialsComponent.FEES_TABLE_TAIL_COLUMNS];
  });

  /** Chart 1: Default fees per TLD per operation. Segments = operations, bar = sum of registrarBilledAmount. */
  feesByOperationChartData = computed(() => {
    const entries = this.costBasisEntries().filter(e => !e.isDefault);
    if (entries.length === 0) return [];
    const grouped = new Map<string, Map<string, number>>();
    for (const e of entries) {
      if (!grouped.has(e.tld)) grouped.set(e.tld, new Map());
      grouped.get(e.tld)!.set(e.operation, e.registrarBilledAmount ?? 0);
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

  /** Chart 2 (gated): Fee split per TLD — RSP Fee and Net to Registry stacked within Registrar Pays. */
  feesEntityBreakdownData = computed(() => {
    const entries = this.costBasisEntries().filter(e => !e.isDefault);
    if (entries.length === 0 || !this.dashService.isColumnVisible('financials.entityBreakdownChart')) return [];
    const grouped = new Map<string, { registrarPays: number; rspFee: number; netToRegistry: number }>();
    for (const e of entries) {
      if (!grouped.has(e.tld)) grouped.set(e.tld, { registrarPays: 0, rspFee: 0, netToRegistry: 0 });
      const g = grouped.get(e.tld)!;
      g.registrarPays += (e.registrarBilledAmount ?? 0);
      g.rspFee += (e.rspRetainedFeeAmount ?? 0);
      g.netToRegistry += (e.netAmountToRegistry ?? 0);
    }
    const tlds = [...grouped.keys()].sort();
    const maxTotal = Math.max(...tlds.map(tld => grouped.get(tld)!.registrarPays), 1);
    return tlds.map(tld => {
      const g = grouped.get(tld)!;
      const total = g.registrarPays;
      const pct = (v: number) => total > 0 ? (v / total) * 100 : 0;
      return {
        tld, total,
        totalWidthPercent: (total / maxTotal) * 100,
        segments: [
          { label: 'Net to Registry', amount: g.netToRegistry, widthPct: pct(g.netToRegistry), color: ENTITY_COLORS.netToRegistry },
          { label: 'RSP Fee', amount: g.rspFee, widthPct: pct(g.rspFee), color: ENTITY_COLORS.rspFee },
        ],
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
      series: [{
        type: 'bar' as const,
        data: operations.map(op => ({
          value: byOp[op],
          itemStyle: { color: OPERATION_COLORS[op] || '#9191A1' },
        })),
      }],
    };
  });

  constructor(public dashService: RegistryDashService) {
    effect(() => {
      // Re-run whenever range changes OR when returning to Overview tab (index 0)
      const tab = this.selectedTab();
      const config = RANGE_CONFIG[this.selectedOverviewRange()];
      if (tab === 0) {
        this.dashService.getRevenueBilling(config.lookbackHours, config.granularity).subscribe();
        this.dashService.getDomainActivity(config.lookbackHours, config.granularity).subscribe();
        this.dashService.getForecasting(config.lookbackHours, config.granularity).subscribe();
      }
    });
  }

  ngOnInit() {
    this.dashService.getCostBasis().subscribe();
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
}
