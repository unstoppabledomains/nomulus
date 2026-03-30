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
import { RegistryDashService } from '../../registry-dash.service';
import { RANGE_CONFIG } from '../revenue-billing/revenue-billing.component';

const TLD_COLORS = [
  '#0D67FE', '#0546B7', '#65A1DA', '#192B55',
  '#00C9FF', '#0A5FEA', '#4A9B30', '#9191A1',
];

@Component({
  selector: 'app-forecasting',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './forecasting.component.html',
  styleUrls: ['./forecasting.component.scss'],
})
export class ForecastingComponent implements OnInit {
  selectedRange = signal('12m');
  rangeKeys = Object.keys(RANGE_CONFIG);

  data = computed(() => this.dashService.forecasting());

  // --- Computed metrics ---

  avgRenewalRate = computed(() => {
    const d = this.data();
    if (!d || d.renewalRates.length === 0) return 0;
    const sum = d.renewalRates.reduce((s, r) => s + r.renewalRate, 0);
    return sum / d.renewalRates.length;
  });

  domainsAtRisk = computed(() => {
    const d = this.data();
    if (!d) return 0;
    return d.renewalRates
      .filter(r => r.renewalRate < 85)
      .reduce((sum, r) => sum + r.renewals + r.deletions, 0);
  });

  // --- ECharts: Stacked area chart — domain expirations by TLD ---

  expirationCurveOptions = computed(() => {
    const d = this.data();
    if (!d || d.expirationCurve.length === 0) return null;

    // Collect unique months and TLDs
    const monthSet = new Set<string>();
    const tldMap = new Map<string, Map<string, number>>();
    for (const pt of d.expirationCurve) {
      monthSet.add(pt.month);
      if (!tldMap.has(pt.tld)) tldMap.set(pt.tld, new Map());
      const monthMap = tldMap.get(pt.tld)!;
      monthMap.set(pt.month, (monthMap.get(pt.month) ?? 0) + pt.count);
    }

    const months = [...monthSet].sort();
    const tlds = [...tldMap.keys()].sort();

    const tldLabels = tlds.map(t => `.${t}`);
    const series = tlds.map((tld, i) => {
      const monthMap = tldMap.get(tld)!;
      return {
        name: `.${tld}`,
        type: 'line' as const,
        stack: 'expirations',
        areaStyle: { opacity: 0.3 },
        emphasis: { focus: 'series' as const },
        data: months.map(m => monthMap.get(m) ?? 0),
        color: TLD_COLORS[i % TLD_COLORS.length],
      };
    });

    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: tldLabels },
      xAxis: { type: 'category' as const, data: months },
      yAxis: { type: 'value' as const },
      dataZoom: [{ type: 'inside' as const, start: 0, end: 100 }],
      series,
    };
  });

  // --- ECharts: Line chart — net domain growth (creates - deletes) ---

  netGrowthOptions = computed(() => {
    const d = this.dashService.domainActivity();
    if (!d || d.activity.length === 0) return null;

    // Calculate net growth per period (creates - deletes)
    const periodMap = new Map<string, number>();
    for (const pt of d.activity) {
      const current = periodMap.get(pt.period) ?? 0;
      if (pt.type === 'CREATES') {
        periodMap.set(pt.period, current + pt.count);
      } else if (pt.type === 'DELETES') {
        periodMap.set(pt.period, current - pt.count);
      }
    }

    const periods = [...periodMap.keys()].sort();
    const values = periods.map(p => periodMap.get(p) ?? 0);

    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'category' as const, data: periods },
      yAxis: { type: 'value' as const },
      series: [
        {
          name: 'Net Growth',
          type: 'line' as const,
          smooth: true,
          areaStyle: {
            opacity: 0.15,
          },
          color: '#0D67FE',
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { type: 'dashed' as const, color: '#9191A1' },
            label: { formatter: '0', position: 'insideEndTop' as const },
            data: [{ yAxis: 0 }],
          },
          data: values,
        },
      ],
    };
  });

  constructor(public dashService: RegistryDashService) {
    // Refetch when selectedRange changes
    effect(() => {
      const range = this.selectedRange();
      const config = RANGE_CONFIG[range];
      if (config) {
        this.dashService.getForecasting(config.lookbackHours, config.granularity).subscribe();
      }
    });
    // Fetch domain activity data for net growth chart
    this.dashService.getDomainActivity().subscribe();
  }

  ngOnInit() {
    // Initial fetch is handled by the effect above
  }

  onRangeChange(range: string) {
    this.selectedRange.set(range);
  }
}
