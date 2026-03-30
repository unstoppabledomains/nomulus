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

import { AfterViewInit, Component, OnInit, ViewChild, computed, effect, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MaterialModule } from '../../material.module';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../ud-echarts';
import { RegistryDashService, CostBasisEntry } from '../registry-dash.service';
import { RevenueBillingComponent } from './revenue-billing/revenue-billing.component';
import { DomainActivityComponent } from './domain-activity/domain-activity.component';
import { ForecastingComponent } from './forecasting/forecasting.component';

const OPERATION_COLORS: Record<string, string> = {
  CREATE: '#0D67FE',
  RENEW: '#0546B7',
  TRANSFER: '#65A1DA',
  RESTORE: '#192B55',
};

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
  selector: 'app-registry-dash-financials',
  standalone: true,
  imports: [CommonModule, MaterialModule, MatSortModule, NgxEchartsDirective, RevenueBillingComponent, DomainActivityComponent, ForecastingComponent],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './financials.component.html',
  styleUrls: ['./financials.component.scss'],
})
export class FinancialsComponent implements OnInit, AfterViewInit {
  selectedTab = signal(0);

  @ViewChild('costBasisSort') costBasisSort!: MatSort;
  dataSource = new MatTableDataSource<CostBasisEntry>([]);

  costBasisEntries = computed(() => this.dashService.costBasis());

  // Filters
  filterTld = signal<string>('all');
  filterOperation = signal<string>('all');
  filterRegistrar = signal<string>('all');

  uniqueTlds = computed(() => {
    const entries = this.costBasisEntries();
    return [...new Set(entries.map(e => e.tld))].sort();
  });

  uniqueOperations = computed(() => {
    const entries = this.costBasisEntries();
    return [...new Set(entries.map(e => e.operation))].sort();
  });

  uniqueRegistrars = computed(() => {
    const entries = this.costBasisEntries();
    const ids = entries.map(e => e.registrarId).filter((id): id is string => !!id);
    return [...new Set(ids)].sort();
  });

  filteredEntries = computed(() => {
    let entries = this.costBasisEntries();
    const tld = this.filterTld();
    const op = this.filterOperation();
    const reg = this.filterRegistrar();
    if (tld !== 'all') entries = entries.filter(e => e.tld === tld);
    if (op !== 'all') entries = entries.filter(e => e.operation === op);
    if (reg !== 'all') entries = entries.filter(e => (e.registrarId || '') === reg);
    return entries;
  });

  hasActiveFilters = computed(() =>
    this.filterTld() !== 'all' || this.filterOperation() !== 'all' || this.filterRegistrar() !== 'all'
  );

  // Group fee schedule by TLD for the summary view
  costBasisByTld = computed(() => {
    const entries = this.costBasisEntries();
    const grouped = new Map<string, CostBasisEntry[]>();
    for (const entry of entries) {
      const key = entry.tld;
      if (!grouped.has(key)) grouped.set(key, []);
      grouped.get(key)!.push(entry);
    }
    return Array.from(grouped.entries()).map(([tld, items]) => ({
      tld,
      entries: items,
      totalCost: items.reduce((sum, e) => sum + e.costAmount, 0),
    }));
  });

  // Build a lookup from pricing rules: tld+operation -> avg registrar fee
  pricingLookup = computed(() => {
    const rules = this.dashService.pricingRules();
    const lookup = new Map<string, { totalPrice: number; count: number }>();
    for (const r of rules) {
      const key = `${r.tld}:${r.operation}`;
      const entry = lookup.get(key) ?? { totalPrice: 0, count: 0 };
      entry.totalPrice += r.priceAmount;
      entry.count++;
      lookup.set(key, entry);
    }
    return lookup;
  });

  // Get the average registrar fee for a given tld+operation
  getRegistrarFee(tld: string, operation: string): number | null {
    const entry = this.pricingLookup()?.get(`${tld}:${operation}`);
    if (!entry || entry.count === 0) return null;
    return entry.totalPrice / entry.count;
  }

  // Summary metrics
  totalTlds = computed(() => this.uniqueTlds().length);
  totalOperations = computed(() => this.uniqueOperations().length);
  averageCost = computed(() => {
    const entries = this.costBasisEntries();
    if (entries.length === 0) return 0;
    return entries.reduce((sum, e) => sum + e.costAmount, 0) / entries.length;
  });

  // Chart data: stacked bar chart grouped by TLD, segments by operation
  chartData = computed(() => {
    const byTld = this.costBasisByTld();
    if (byTld.length === 0) return [];
    const maxTotal = Math.max(...byTld.map(g => g.totalCost), 1);
    return byTld.map(group => {
      const segments = group.entries.map(e => ({
        operation: e.operation,
        amount: e.costAmount,
        widthPercent: (e.costAmount / maxTotal) * 100,
        color: OPERATION_COLORS[e.operation] || '#9191A1',
      }));
      return {
        tld: group.tld,
        total: group.totalCost,
        totalWidthPercent: (group.totalCost / maxTotal) * 100,
        segments,
      };
    });
  });

  // Registrar markup — compare registrar prices to registry fees
  pricingSpreadData = computed(() => {
    const rules = this.dashService.pricingRules();
    const costBasis = this.costBasisEntries();
    if (rules.length === 0 || costBasis.length === 0) return [];

    // Build cost lookup: tld+operation -> cost
    const costLookup = new Map<string, number>();
    for (const cb of costBasis) {
      const key = `${cb.tld}:${cb.operation}:${cb.registrarId || ''}`;
      costLookup.set(key, cb.costAmount);
      // Also set default (no registrar) fallback
      if (!cb.registrarId) {
        costLookup.set(`${cb.tld}:${cb.operation}:default`, cb.costAmount);
      }
    }

    const maxSpread = { value: 0 };
    const spreads = rules.map(r => {
      const cost = costLookup.get(`${r.tld}:${r.operation}:${r.registrarId}`)
        ?? costLookup.get(`${r.tld}:${r.operation}:default`)
        ?? r.defaultPrice
        ?? 0;
      const spread = r.priceAmount - cost;
      if (Math.abs(spread) > maxSpread.value) maxSpread.value = Math.abs(spread);
      return {
        registrarId: r.registrarId,
        tld: r.tld,
        operation: r.operation,
        price: r.priceAmount,
        cost,
        spread,
        currency: r.priceCurrency,
      };
    });

    const maxAbs = maxSpread.value || 1;
    return spreads.map(s => ({
      ...s,
      barWidthPct: (Math.abs(s.spread) / maxAbs) * 50,
      isPositive: s.spread >= 0,
    }));
  });

  private static readonly COST_MONEY_KEYS: Record<string, string> = {
    registrarFee: 'financials.registrarFee',
    rspCut: 'financials.rspCut',
    costAmount: 'financials.costAmount',
  };

  private static readonly ALL_COST_COLUMNS = [
    'tld', 'operation', 'registrarId', 'registrarFee', 'rspCut',
    'costAmount', 'costCurrency', 'effectiveDate', 'notes',
  ];

  // --- Financials Overview charts (one per sub-tab) ---

  /** From Revenue & Billing: Revenue by Operation bar chart */
  overviewRevenueByOpOptions = computed(() => {
    const d = this.dashService.revenueBilling();
    if (!d) return null;
    const byOp = d.totals.byOperation;
    const operations = Object.keys(byOp);
    if (operations.length === 0) return null;
    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'category' as const, data: operations },
      yAxis: { type: 'value' as const },
      series: [{
        type: 'bar' as const,
        data: operations.map(op => ({
          value: byOp[op],
          itemStyle: { color: OPERATION_COLORS[op] || '#9191A1' },
        })),
      }],
    };
  });

  /** From Domain Activity: Activity breakdown grouped bar chart */
  overviewActivityByTldOptions = computed(() => {
    const d = this.dashService.domainActivity();
    if (!d || d.activity.length === 0) return null;
    const tldSet = new Set<string>();
    const typeSet = new Set<string>();
    const data = new Map<string, Map<string, number>>();
    for (const pt of d.activity) {
      tldSet.add(pt.tld);
      typeSet.add(pt.type);
      if (!data.has(pt.tld)) data.set(pt.tld, new Map());
      data.get(pt.tld)!.set(pt.type, (data.get(pt.tld)!.get(pt.type) ?? 0) + pt.count);
    }
    const tlds = [...tldSet].sort();
    const types = [...typeSet].sort();
    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: types },
      xAxis: { type: 'category' as const, data: tlds, axisLabel: { rotate: 30 } },
      yAxis: { type: 'value' as const },
      series: types.map(type => ({
        name: type,
        type: 'bar' as const,
        data: tlds.map(tld => data.get(tld)?.get(type) ?? 0),
        color: ACTIVITY_COLORS[type] || '#9191A1',
      })),
    };
  });

  /** From Forecasting: Domain expirations stacked area chart */
  overviewExpirationsOptions = computed(() => {
    const d = this.dashService.forecasting();
    if (!d || d.expirationCurve.length === 0) return null;
    const monthSet = new Set<string>();
    const tldMap = new Map<string, Map<string, number>>();
    for (const pt of d.expirationCurve) {
      monthSet.add(pt.month);
      if (!tldMap.has(pt.tld)) tldMap.set(pt.tld, new Map());
      tldMap.get(pt.tld)!.set(pt.month, (tldMap.get(pt.tld)!.get(pt.month) ?? 0) + pt.count);
    }
    const months = [...monthSet].sort();
    const tlds = [...tldMap.keys()].sort();
    return {
      tooltip: { trigger: 'axis' as const },
      legend: { data: tlds },
      xAxis: { type: 'category' as const, data: months },
      yAxis: { type: 'value' as const },
      series: tlds.map((tld, i) => ({
        name: tld,
        type: 'line' as const,
        stack: 'expirations',
        areaStyle: { opacity: 0.3 },
        emphasis: { focus: 'series' as const },
        data: months.map(m => tldMap.get(tld)!.get(m) ?? 0),
        color: TLD_COLORS[i % TLD_COLORS.length],
      })),
    };
  });

  /** Overview metric: total revenue */
  overviewTotalRevenue = computed(() => this.dashService.revenueBilling()?.totals.totalRevenue ?? 0);
  overviewCurrency = computed(() => this.dashService.revenueBilling()?.totals.currency ?? 'USD');

  /** Overview metric: net growth (creates - deletes) */
  overviewNetGrowth = computed(() => {
    const d = this.dashService.domainActivity();
    if (!d) return 0;
    const creates = d.activity.filter(pt => pt.type === 'CREATES').reduce((s, pt) => s + pt.count, 0);
    const deletes = d.activity.filter(pt => pt.type === 'DELETES').reduce((s, pt) => s + pt.count, 0);
    return creates - deletes;
  });

  /** Overview metric: avg renewal rate */
  overviewAvgRenewalRate = computed(() => {
    const d = this.dashService.forecasting();
    if (!d || d.renewalRates.length === 0) return 0;
    return d.renewalRates.reduce((s, r) => s + r.renewalRate, 0) / d.renewalRates.length;
  });

  costBasisColumns = computed(() => {
    return FinancialsComponent.ALL_COST_COLUMNS.filter(col => {
      const key = FinancialsComponent.COST_MONEY_KEYS[col];
      return !key || this.dashService.isColumnVisible(key);
    });
  });

  byTldColumns = computed(() => {
    const all = ['operation', 'registrarId', 'registrarFee', 'rspCut', 'costAmount', 'notes'];
    return all.filter(col => {
      const key = FinancialsComponent.COST_MONEY_KEYS[col];
      return !key || this.dashService.isColumnVisible(key);
    });
  });

  constructor(public dashService: RegistryDashService) {
    // Sync filtered entries into dataSource
    effect(() => {
      this.dataSource.data = this.filteredEntries();
    });
  }

  ngOnInit() {
    this.dashService.getCostBasis().subscribe();
    this.dashService.getPricing().subscribe();
    // Pre-load data for the overview charts
    this.dashService.getRevenueBilling(8760, 'month').subscribe();
    this.dashService.getDomainActivity(8760, 'month').subscribe();
    this.dashService.getForecasting(8760, 'month').subscribe();
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.costBasisSort;
  }

  onTabChange(index: number) {
    this.selectedTab.set(index);
  }

  clearFilters() {
    this.filterTld.set('all');
    this.filterOperation.set('all');
    this.filterRegistrar.set('all');
  }

  getRegistrarLabel(registrarId: string | null | undefined): string {
    return registrarId || 'All (default)';
  }

  getOperationColor(operation: string): string {
    return OPERATION_COLORS[operation] || '#9191A1';
  }
}
