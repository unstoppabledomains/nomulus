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

import { Component, DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { combineLatest, switchMap, EMPTY, catchError } from 'rxjs';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective } from 'ngx-echarts';
import { RegistryDashService } from '../registry-dash.service';
import { UD_ECHARTS_PROVIDER } from '../ud-echarts';

const CHART_COLORS = [
  '#0D67FE', '#0546B7', '#65A1DA', '#192B55',
  '#00C9FF', '#0A5FEA', '#4A9B30', '#9191A1',
  '#3B82F6', '#7A7A85',
];

const ACTIVITY_COLORS: Record<string, string> = {
  CREATES: '#0D67FE',
  RENEWS: '#0546B7',
  TRANSFERS: '#65A1DA',
  DELETES: '#192B55',
  RESTORES: '#00C9FF',
};

@Component({
  selector: 'app-registry-dash-overview',
  imports: [MaterialModule, CommonModule, NgxEchartsDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './overview.component.html',
  styleUrls: ['./overview.component.scss'],
})
export class OverviewComponent {
  private destroyRef = inject(DestroyRef);
  barChartData = computed(() => {
    const overview = this.dashService.overview();
    if (!overview) return [];
    const rows = overview.domainsByRegistrar.filter(r => r.count > 0);
    const max = Math.max(...rows.map(r => r.count), 1);
    return rows.map((r, i) => ({
      name: r.name || r.registrarId,
      registrarId: r.registrarId,
      count: r.count,
      widthPct: (r.count / max) * 100,
      color: CHART_COLORS[i % CHART_COLORS.length],
    }));
  });

  activityLineOptions = computed(() => {
    const d = this.dashService.domainActivity();
    if (!d || d.activity.length === 0) return null;
    const periodSet = new Set<string>();
    const typeMap = new Map<string, Map<string, number>>();
    for (const pt of d.activity) {
      periodSet.add(pt.period);
      if (!typeMap.has(pt.type)) typeMap.set(pt.type, new Map());
      const periodMap = typeMap.get(pt.type)!;
      periodMap.set(pt.period, (periodMap.get(pt.period) ?? 0) + pt.count);
    }
    const periods = [...periodSet].sort();
    const types = [...typeMap.keys()].sort();
    const series = types.map(type => {
      const periodMap = typeMap.get(type)!;
      return {
        name: type,
        type: 'line' as const,
        smooth: true,
        emphasis: { focus: 'series' as const },
        data: periods.map(p => periodMap.get(p) ?? 0),
        color: ACTIVITY_COLORS[type] || '#9191A1',
      };
    });
    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: types },
      xAxis: { type: 'category' as const, data: periods },
      yAxis: { type: 'value' as const, name: 'Domains', nameLocation: 'middle' as const, nameGap: 40 },
      dataZoom: [{ type: 'inside' as const, start: 0, end: 100 }],
      series,
    };
  });

  renewalRateOptions = computed(() => {
    const d = this.dashService.forecasting();
    if (!d || d.renewalRates.length === 0) return null;
    const sorted = [...d.renewalRates].sort((a, b) => b.renewalRate - a.renewalRate);
    const tlds = sorted.map(r => `.${r.tld}`);
    const rates = sorted.map(r => r.renewalRate);
    const maxRate = Math.max(...rates, 0);
    const dynamicMax = Math.min(100, Math.ceil((maxRate + 10) / 10) * 10);
    return {
      tooltip: {
        trigger: 'axis' as const,
        formatter: (params: any) => {
          const p = Array.isArray(params) ? params[0] : params;
          return `${p.name}: ${p.value.toFixed(1)}%`;
        },
      },
      xAxis: {
        type: 'value' as const,
        min: 0,
        max: dynamicMax,
        axisLabel: { formatter: '{value}%' },
      },
      yAxis: {
        type: 'category' as const,
        data: tlds,
        inverse: true,
      },
      series: [
        {
          type: 'bar' as const,
          data: rates.map(rate => ({
            value: rate,
            itemStyle: {
              color: rate > 85 ? '#4A9B30' : rate >= 70 ? '#d97706' : '#c53030',
            },
          })),
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { type: 'dashed' as const, color: '#9191A1' },
            label: { formatter: '85% benchmark', position: 'insideEndTop' as const },
            data: [{ xAxis: 85 }],
          },
        },
      ],
    };
  });

  constructor(protected dashService: RegistryDashService) {
    // Re-fetch when global filters change
    combineLatest([
      toObservable(this.dashService.selectedTlds),
      toObservable(this.dashService.selectedRegistrarIds),
    ]).pipe(
      switchMap(([tlds, regIds]) => {
        const ft = tlds.length > 0 ? tlds : undefined;
        const fr = regIds.length > 0 ? regIds : undefined;
        this.dashService.getOverview(ft, fr).subscribe();
        this.dashService.getDomainActivity(undefined, undefined, ft, fr).subscribe();
        return this.dashService.getForecasting(undefined, undefined, ft, fr).pipe(
          catchError(() => EMPTY)
        );
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }
}
