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
import { MaterialModule } from '../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../ud-echarts';
import { RegistryDashService, RANGE_CONFIG } from '../registry-dash.service';
import { DrillDownService } from '../drilldown/drilldown.service';
import { withDrillDown } from '../ud-echarts';
import { LongPressDirective } from '../drilldown/long-press.directive';
import { FilterPanelComponent } from '../filter-panel/filter-panel.component';

const ACTIVITY_COLORS: Record<string, string> = {
  CREATES: '#0D67FE',
  RENEWS: '#0546B7',
  TRANSFERS: '#65A1DA',
  DELETES: '#192B55',
  RESTORES: '#00C9FF',
};

const TLD_COLORS = [
  '#0D67FE', '#0546B7', '#65A1DA', '#192B55',
  '#00C9FF', '#0A5FEA', '#4A9B30', '#9191A1',
];

@Component({
  selector: 'app-domain-activity',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective, LongPressDirective, FilterPanelComponent],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './domain-activity.component.html',
  styleUrls: ['./domain-activity.component.scss'],
})
export class DomainActivityComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  lastHoveredActivityByTld: any = null;
  lastHoveredDomainCounts: any = null;

  data = computed(() => this.dashService.domainActivity());

  // Summary metrics
  totalTransactions = computed(() => {
    const d = this.data();
    if (!d || d.activity.length === 0) return 0;
    return d.activity.reduce((sum, pt) => sum + pt.count, 0);
  });

  netGrowth = computed(() => {
    const d = this.data();
    if (!d || d.activity.length === 0) return 0;
    const creates = d.activity
      .filter(pt => pt.type === 'CREATES')
      .reduce((sum, pt) => sum + pt.count, 0);
    const deletes = d.activity
      .filter(pt => pt.type === 'DELETES')
      .reduce((sum, pt) => sum + pt.count, 0);
    return creates - deletes;
  });

  mostActiveTld = computed(() => {
    const d = this.data();
    if (!d || d.activity.length === 0) return '—';
    const byTld = new Map<string, number>();
    for (const pt of d.activity) {
      byTld.set(pt.tld, (byTld.get(pt.tld) ?? 0) + pt.count);
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

  // ECharts: Activity breakdown grouped bar chart (one series per activity type, grouped by TLD)
  activityByTldOptions = computed(() => {
    const d = this.data();
    if (!d || d.activity.length === 0) return null;

    // Group by TLD and type
    const tldSet = new Set<string>();
    const typeSet = new Set<string>();
    const data = new Map<string, Map<string, number>>();
    for (const pt of d.activity) {
      tldSet.add(pt.tld);
      typeSet.add(pt.type);
      if (!data.has(pt.tld)) data.set(pt.tld, new Map());
      const typeMap = data.get(pt.tld)!;
      typeMap.set(pt.type, (typeMap.get(pt.type) ?? 0) + pt.count);
    }

    const tlds = [...tldSet].sort();
    const tldLabels = tlds.map(t => `.${t}`);
    const types = [...typeSet].sort();

    const series = types.map(type => withDrillDown({
      name: type,
      type: 'bar' as const,
      data: tlds.map(tld => data.get(tld)?.get(type) ?? 0),
      color: ACTIVITY_COLORS[type] || '#9191A1',
    }));

    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: types },
      xAxis: { type: 'category' as const, data: tldLabels, axisLabel: { rotate: 30 } },
      yAxis: { type: 'value' as const, name: 'Domains', nameLocation: 'middle' as const, nameGap: 40 },
      series,
    };
  });

  // ECharts: Domain counts horizontal bar chart (one bar per TLD)
  domainCountsBarOptions = computed(() => {
    const d = this.data();
    if (!d || !d.currentCounts) return null;
    const entries = Object.entries(d.currentCounts);
    if (entries.length === 0) return null;

    // Sort descending by count
    entries.sort((a, b) => b[1] - a[1]);
    const tlds = entries.map(([tld]) => `.${tld}`);
    const counts = entries.map(([, count]) => count);

    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'value' as const, name: 'Domains', nameLocation: 'middle' as const, nameGap: 25 },
      yAxis: {
        type: 'category' as const,
        data: tlds,
        inverse: true,
      },
      series: [
        withDrillDown({
          type: 'bar' as const,
          data: counts.map((val, i) => ({
            value: val,
            itemStyle: { color: TLD_COLORS[i % TLD_COLORS.length] },
          })),
        }),
      ],
    };
  });

  constructor(
    public dashService: RegistryDashService,
    private drillDown: DrillDownService,
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
        return this.dashService.getDomainActivity(
          config.lookbackHours, config.granularity, ft, fr
        ).pipe(catchError(() => EMPTY));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }

  ngOnInit() {
    // Initial fetch is handled by the observable above
  }

  onActivityByTldClick(event: any) {
    if (event.name) this.drillDown.applyTldFilter(event.name);
  }

  onActivityByTldContext(event: any) {
    event.event?.event?.preventDefault();
    if (event.name) this.drillDown.drillDownActivityByTld(event.name);
  }

  onDomainCountsClick(event: any) {
    if (event.name) this.drillDown.applyTldFilter(event.name);
  }

  onDomainCountsContext(event: any) {
    event.event?.event?.preventDefault();
    if (event.name) this.drillDown.drillDownDomainCountsByTld(event.name);
  }

  onActivityByTldLongPress() {
    if (this.lastHoveredActivityByTld?.name) {
      this.drillDown.drillDownActivityByTld(this.lastHoveredActivityByTld.name);
    }
  }

  onDomainCountsLongPress() {
    if (this.lastHoveredDomainCounts?.name) {
      this.drillDown.drillDownDomainCountsByTld(this.lastHoveredDomainCounts.name);
    }
  }
}
