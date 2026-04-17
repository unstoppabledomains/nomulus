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
import { MaterialModule } from '../../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../../ud-echarts';
import { RegistryDashService } from '../../registry-dash.service';
import { RANGE_CONFIG } from '../revenue-billing/revenue-billing.component';

const TLD_COLORS = [
  '#0D67FE', '#0546B7', '#65A1DA', '#192B55',
  '#00C9FF', '#0A5FEA', '#4A9B30', '#9191A1',
];

const FORECAST_HORIZON = 3; // months to project forward

type ForecastMethod = 'none' | 'trend' | 'confidence' | 'ema' | 'seasonal';

@Component({
  selector: 'app-forecasting',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './forecasting.component.html',
  styleUrls: ['./forecasting.component.scss'],
})
export class ForecastingComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  selectedRange = signal('12m');
  rangeKeys = Object.keys(RANGE_CONFIG);
  forecastMethod = signal<ForecastMethod>('trend');

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
    const series: any[] = tlds.map((tld, i) => {
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
      yAxis: { type: 'value' as const, name: 'Domains', nameLocation: 'middle' as const, nameGap: 40 },
      dataZoom: [{ type: 'inside' as const, start: 0, end: 100 }],
      series,
    };
  });

  // --- ECharts: Net Growth Projection with forecasting methods ---

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
    const method = this.forecastMethod();

    // Generate future period labels
    const futurePeriods = this.generateFuturePeriods(periods, FORECAST_HORIZON);
    const allPeriods = [...periods, ...futurePeriods];

    const series: any[] = [
      {
        name: 'Net Growth',
        type: 'line' as const,
        smooth: true,
        areaStyle: { opacity: 0.15 },
        color: '#0D67FE',
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed' as const, color: '#9191A1' },
          label: { formatter: '0', position: 'insideEndTop' as const },
          data: [{ yAxis: 0 }],
        },
        data: method !== 'none'
          ? [...values, ...new Array(FORECAST_HORIZON).fill(null)]
          : values,
      },
    ];

    if (method === 'trend' || method === 'confidence') {
      const { slope, intercept } = this.linearRegression(values);
      const trendForecast = futurePeriods.map((_, i) => {
        const x = values.length + i;
        return Math.round(slope * x + intercept);
      });
      // Extend trend through historical + forecast
      const trendAll = values.map((_, i) => Math.round(slope * i + intercept));
      series.push({
        name: 'Trend Line',
        type: 'line' as const,
        smooth: true,
        lineStyle: { type: 'dashed' as const, width: 2 },
        symbol: 'none',
        color: '#d97706',
        data: [...trendAll, ...trendForecast],
      });

      if (method === 'confidence') {
        const residuals = values.map((v, i) => v - (slope * i + intercept));
        const sigma = Math.sqrt(residuals.reduce((s, r) => s + r * r, 0) / residuals.length);
        const upper = allPeriods.map((_, i) => Math.round(slope * i + intercept + sigma));
        const lower = allPeriods.map((_, i) => Math.round(slope * i + intercept - sigma));
        series.push(
          {
            name: 'Upper Band (+1\u03c3)',
            type: 'line' as const,
            smooth: true,
            lineStyle: { opacity: 0 },
            symbol: 'none',
            data: upper,
          },
          {
            name: 'Lower Band (-1\u03c3)',
            type: 'line' as const,
            smooth: true,
            lineStyle: { opacity: 0 },
            areaStyle: { opacity: 0.1, color: '#d97706' },
            symbol: 'none',
            data: lower,
            stack: 'confidence',
          },
          {
            name: 'Confidence Range',
            type: 'line' as const,
            smooth: true,
            lineStyle: { opacity: 0 },
            areaStyle: { opacity: 0.15, color: '#d97706' },
            symbol: 'none',
            data: upper.map((u, i) => u - lower[i]),
            stack: 'confidence',
          },
        );
      }
    }

    if (method === 'ema') {
      const alpha = 0.3;
      const smoothed = this.exponentialSmoothing(values, alpha);
      // Project forward using last smoothed value and smoothed slope
      const lastSmoothed = smoothed[smoothed.length - 1];
      const prevSmoothed = smoothed.length > 1 ? smoothed[smoothed.length - 2] : lastSmoothed;
      const slopeEma = lastSmoothed - prevSmoothed;
      const emaForecast = futurePeriods.map((_, i) =>
        Math.round(lastSmoothed + slopeEma * (i + 1))
      );
      series.push({
        name: `EMA (\u03b1=0.3)`,
        type: 'line' as const,
        smooth: true,
        lineStyle: { type: 'dashed' as const, width: 2 },
        symbol: 'none',
        color: '#4A9B30',
        data: [...smoothed.map(v => Math.round(v)), ...emaForecast],
      });
    }

    if (method === 'seasonal') {
      const seasonalForecast = this.seasonalNaive(values, futurePeriods.length);
      // Show seasonal forecast extending from end of historical data
      const seasonalData = [
        ...new Array(values.length).fill(null),
        ...seasonalForecast,
      ];
      series.push({
        name: 'Seasonal Forecast',
        type: 'line' as const,
        smooth: false,
        lineStyle: { type: 'dashed' as const, width: 2 },
        symbol: 'circle',
        symbolSize: 6,
        color: '#c53030',
        data: seasonalData,
      });
    }

    return {
      tooltip: { trigger: 'axis' as const },
      legend: {
        data: series.filter(s =>
          !s.name.startsWith('Upper') && !s.name.startsWith('Lower') && !s.name.startsWith('Confidence')
        ).map(s => s.name),
      },
      xAxis: { type: 'category' as const, data: allPeriods },
      yAxis: { type: 'value' as const, name: 'Domains', nameLocation: 'middle' as const, nameGap: 40 },
      series,
    };
  });

  constructor(public dashService: RegistryDashService) {
    combineLatest([
      toObservable(this.selectedRange),
      toObservable(this.dashService.selectedTlds),
      toObservable(this.dashService.selectedRegistrarIds),
    ]).pipe(
      switchMap(([range, tlds, regIds]) => {
        const config = RANGE_CONFIG[range];
        const ft = tlds.length > 0 ? tlds : undefined;
        const fr = regIds.length > 0 ? regIds : undefined;
        this.dashService.getDomainActivity(
          config.lookbackHours, config.granularity, ft, fr
        ).subscribe();
        return this.dashService.getForecasting(
          config.lookbackHours, config.granularity, ft, fr
        ).pipe(catchError(() => EMPTY));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }

  ngOnInit() {
    // Initial fetch is handled by the observable above
  }

  onRangeChange(range: string) {
    this.selectedRange.set(range);
  }

  onForecastMethodChange(method: string) {
    this.forecastMethod.set(method as ForecastMethod);
  }

  // --- Forecasting utilities ---

  private linearRegression(values: number[]): { slope: number; intercept: number } {
    const n = values.length;
    if (n < 2) return { slope: 0, intercept: values[0] ?? 0 };
    const xMean = (n - 1) / 2;
    const yMean = values.reduce((s, v) => s + v, 0) / n;
    let num = 0;
    let den = 0;
    for (let i = 0; i < n; i++) {
      num += (i - xMean) * (values[i] - yMean);
      den += (i - xMean) * (i - xMean);
    }
    const slope = den !== 0 ? num / den : 0;
    const intercept = yMean - slope * xMean;
    return { slope, intercept };
  }

  private exponentialSmoothing(values: number[], alpha: number): number[] {
    if (values.length === 0) return [];
    const smoothed: number[] = [values[0]];
    for (let i = 1; i < values.length; i++) {
      smoothed.push(alpha * values[i] + (1 - alpha) * smoothed[i - 1]);
    }
    return smoothed;
  }

  private seasonalNaive(values: number[], horizonCount: number): number[] {
    const period = 12; // monthly seasonality
    const forecast: number[] = [];
    for (let i = 0; i < horizonCount; i++) {
      const lookback = values.length - period + i;
      if (lookback >= 0 && lookback < values.length) {
        forecast.push(values[lookback]);
      } else {
        // Fallback to simple average if not enough history
        const avg = values.length > 0
          ? Math.round(values.reduce((s, v) => s + v, 0) / values.length)
          : 0;
        forecast.push(avg);
      }
    }
    return forecast;
  }

  private generateFuturePeriods(periods: string[], count: number): string[] {
    if (periods.length === 0) return [];
    const last = periods[periods.length - 1];
    // Try to parse as YYYY-MM format
    const match = last.match(/^(\d{4})-(\d{2})/);
    if (match) {
      let year = parseInt(match[1], 10);
      let month = parseInt(match[2], 10);
      const result: string[] = [];
      for (let i = 0; i < count; i++) {
        month++;
        if (month > 12) { month = 1; year++; }
        result.push(`${year}-${String(month).padStart(2, '0')}`);
      }
      return result;
    }
    // Fallback: append numbered periods
    return Array.from({ length: count }, (_, i) => `+${i + 1}`);
  }
}
