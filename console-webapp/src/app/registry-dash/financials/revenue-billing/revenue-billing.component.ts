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
import { MaterialModule } from '../../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../../ud-echarts';
import { RegistryDashService, RevenueDataPoint } from '../../registry-dash.service';

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
  imports: [CommonModule, MaterialModule, NgxEchartsDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './revenue-billing.component.html',
  styleUrls: ['./revenue-billing.component.scss'],
})
export class RevenueBillingComponent implements OnInit {
  selectedMonths = signal(12);

  data = computed(() => this.dashService.revenueBilling());

  // Summary metrics
  totalRevenue = computed(() => {
    const d = this.data();
    return d?.totals.totalRevenue ?? 0;
  });

  avgMonthlyRevenue = computed(() => {
    const d = this.data();
    if (!d || d.monthlyRevenue.length === 0) return 0;
    const months = new Set(d.monthlyRevenue.map(p => p.month));
    return months.size > 0 ? d.totals.totalRevenue / months.size : 0;
  });

  topTld = computed(() => {
    const d = this.data();
    if (!d || d.monthlyRevenue.length === 0) return '—';
    const byTld = new Map<string, number>();
    for (const pt of d.monthlyRevenue) {
      byTld.set(pt.tld, (byTld.get(pt.tld) ?? 0) + pt.amount);
    }
    let best = '';
    let bestVal = 0;
    for (const [tld, val] of byTld) {
      if (val > bestVal) {
        best = tld;
        bestVal = val;
      }
    }
    return best || '—';
  });

  currency = computed(() => this.data()?.totals.currency ?? 'USD');

  // ECharts: Revenue area chart (one series per TLD)
  revenueLineOptions = computed(() => {
    const d = this.data();
    if (!d || d.monthlyRevenue.length === 0) return null;

    // Group by TLD
    const tldMap = new Map<string, Map<string, number>>();
    const allMonths = new Set<string>();
    for (const pt of d.monthlyRevenue) {
      allMonths.add(pt.month);
      if (!tldMap.has(pt.tld)) tldMap.set(pt.tld, new Map());
      const monthMap = tldMap.get(pt.tld)!;
      monthMap.set(pt.month, (monthMap.get(pt.month) ?? 0) + pt.amount);
    }

    const months = [...allMonths].sort();
    const tlds = [...tldMap.keys()].sort();

    const series = tlds.map((tld, i) => {
      const monthMap = tldMap.get(tld)!;
      return {
        name: tld,
        type: 'line' as const,
        stack: 'revenue',
        areaStyle: { opacity: 0.15 },
        emphasis: { focus: 'series' as const },
        data: months.map(m => monthMap.get(m) ?? 0),
        color: TLD_COLORS[i % TLD_COLORS.length],
      };
    });

    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: tlds },
      xAxis: { type: 'category' as const, data: months },
      yAxis: { type: 'value' as const },
      dataZoom: [{ type: 'inside' as const, start: 0, end: 100 }],
      series,
    };
  });

  // ECharts: Operation breakdown bar chart
  operationBarOptions = computed(() => {
    const d = this.data();
    if (!d) return null;
    const byOp = d.totals.byOperation;
    const operations = Object.keys(byOp);
    if (operations.length === 0) return null;

    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'category' as const, data: operations },
      yAxis: { type: 'value' as const },
      series: [
        {
          type: 'bar' as const,
          data: operations.map(op => ({
            value: byOp[op],
            itemStyle: { color: OPERATION_COLORS[op] || '#9191A1' },
          })),
        },
      ],
    };
  });

  constructor(public dashService: RegistryDashService) {
    // Refetch when selectedMonths changes
    effect(() => {
      const months = this.selectedMonths();
      this.dashService.getRevenueBilling(months).subscribe();
    });
  }

  ngOnInit() {
    // Initial fetch is handled by the effect above
  }

  onMonthsChange(months: number) {
    this.selectedMonths.set(months);
  }
}
