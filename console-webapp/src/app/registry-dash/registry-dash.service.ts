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

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, WritableSignal, computed, signal } from '@angular/core';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { BackendService } from '../shared/services/backend.service';
import { DateRange } from './explore/explore.models';
import { AiModelCatalog, AiModelCatalogResponse } from './ai/ai-analysis.models';

export interface OverviewData {
  totalDomains: number;
  activeRegistrars: number;
  domainsByRegistrar: RegistrarDomainCount[];
}

export interface RegistrarDomainCount {
  registrarId: string;
  name: string;
  count: number;
}

export interface PortfolioEntry {
  registrarId: string;
  registrarName: string;
  state: string;
  domainCount: number;
  allowedTlds: string[];
}

export interface PricingRule {
  id?: number;
  registrarId: string;
  tld: string;
  operation: string;
  priceAmount: number;
  priceCurrency: string;
  effectiveDate: string;
  expiryDate?: string;
  isActive: boolean;
  defaultPrice?: number;
  defaultPriceCurrency?: string;
}

export const DEFAULT_TLD = '*';

export interface CostBasisEntry {
  id?: number;
  tld: string;
  operation: string;
  rspRetainedFeeAmount: number;
  costCurrency: string;
  currency?: string;
  effectiveDate: string;
  notes?: string;
  isDefault?: boolean;
  inheritedFromDefault?: boolean;
  // Enriched fields computed by the backend
  registrarBilledAmount?: number;
  netAmountToRegistry?: number;
}

export interface RoRegistryTld {
  id?: number;
  tld: string;
}

export interface RoRegistryUser {
  id?: number;
  userEmail: string;
}

export interface RoRegistry {
  id?: number;
  name: string;
  createdAt?: string;
  tlds: RoRegistryTld[];
  users: RoRegistryUser[];
  settings?: string;
}

export interface SystemRegistrar {
  registrarId: string;
  registrarName: string;
  allowedTlds: string[];
}

export interface SystemInfo {
  tlds: string[];
  registrars: SystemRegistrar[];
}

export interface AdminData {
  registries: RoRegistry[];
  systemInfo: SystemInfo;
  aiModelCatalog?: AiModelCatalog;
  aiModelCatalogFetchedAt?: string;
}

export interface RevenueDataPoint {
  period: string;
  tld: string;
  operation: string;
  amount: number;
  netAmountToRegistry: number;
  currency: string;
}

export interface RevenueTotals {
  totalRevenue: number;
  totalNetAmountToRegistry: number;
  currency: string;
  byOperation: Record<string, number>;
  byOperationNetAmountToRegistry: Record<string, number>;
}

export interface RevenueBillingData {
  periodRevenue: RevenueDataPoint[];
  totals: RevenueTotals;
}

export interface ActivityDataPoint {
  period: string;
  tld: string;
  type: string;
  count: number;
}

export interface DomainActivityData {
  activity: ActivityDataPoint[];
  currentCounts: Record<string, number>;
}

export interface ExpirationDataPoint {
  month: string;
  tld: string;
  count: number;
}

export interface RenewalRateEntry {
  tld: string;
  renewals: number;
  deletions: number;
  renewalRate: number;
}

export interface ForecastingData {
  expirationCurve: ExpirationDataPoint[];
  renewalRates: RenewalRateEntry[];
}

export interface TldFeeEntry {
  tld: string;
  operation: string;
  defaultPrice: number;
  currency: string;
}

export interface EffectiveFeeEntry {
  registrarId: string;
  registrarName: string;
  tld: string;
  operation: string;
  price: number;
  currency: string;
  source: 'Default' | 'Custom';
}

export interface FilterRegistrarOption {
  registrarId: string;
  registrarName: string;
  allowedTlds: string[];
}

export interface FilterOptionsData {
  tlds: string[];
  registrars: FilterRegistrarOption[];
}

export interface RangeConfigEntry {
  lookbackHours: number;
  granularity: string;
}

export const RANGE_CONFIG: Record<string, RangeConfigEntry> = {
  '6h': { lookbackHours: 6, granularity: '15min' },
  '12h': { lookbackHours: 12, granularity: 'hour' },
  '1d': { lookbackHours: 24, granularity: 'hour' },
  '7d': { lookbackHours: 168, granularity: 'day' },
  '30d': { lookbackHours: 720, granularity: 'day' },
  '3m': { lookbackHours: 2160, granularity: 'month' },
  '6m': { lookbackHours: 4380, granularity: 'month' },
  '12m': { lookbackHours: 8760, granularity: 'month' },
  '24m': { lookbackHours: 17520, granularity: 'month' },
};

export const RANGE_KEYS = Object.keys(RANGE_CONFIG);

export function computeDateRange(lookbackHours: number): DateRange {
  const end = new Date();
  const start = new Date(end.getTime() - lookbackHours * 3600_000);
  return {
    start: start.toISOString().split('T')[0],
    end: end.toISOString().split('T')[0],
  };
}

export const RANGE_LABELS: Record<string, string> = {
  '6h': '6 Hours', '12h': '12 Hours', '1d': '1 Day', '7d': '7 Days',
  '30d': '30 Days', '3m': '3 Months', '6m': '6 Months',
  '12m': '12 Months', '24m': '24 Months',
};

@Injectable({ providedIn: 'root' })
export class RegistryDashService {
  overview = signal<OverviewData | undefined>(undefined);
  portfolio = signal<PortfolioEntry[]>([]);
  pricingRules = signal<PricingRule[]>([]);
  costBasis = signal<CostBasisEntry[]>([]);
  adminData = signal<AdminData | undefined>(undefined);
  revenueBilling = signal<RevenueBillingData | undefined>(undefined);
  domainActivity = signal<DomainActivityData | undefined>(undefined);
  forecasting = signal<ForecastingData | undefined>(undefined);
  tldFees = signal<TldFeeEntry[]>([]);
  effectiveFees = signal<EffectiveFeeEntry[]>([]);
  /** Default visibility — RSP Fee and Net to Registry hidden by default for non-admin users. */
  static readonly DEFAULT_VISIBILITY: Record<string, boolean> = {
    'financials.feesRspPays': false,
    'financials.feesNetToRegistry': false,
    'financials.entityBreakdownChart': false,
  };

  columnVisibility = signal<Record<string, boolean>>({ ...RegistryDashService.DEFAULT_VISIBILITY });
  settingsCache = signal<Record<string, any>>({});
  loading = signal(false);
  error = signal<string | undefined>(undefined);

  // --- Global filter state ---
  filterOptions = signal<FilterOptionsData | undefined>(undefined);
  selectedRegistrarIds = signal<string[]>([]);
  selectedTlds = signal<string[]>([]);
  filterPanelExpanded = signal(false);

  // --- Global timeframe state ---
  selectedTimeRange: WritableSignal<string> = signal('12m');
  selectedRangeConfig = computed(() => RANGE_CONFIG[this.selectedTimeRange()]);
  timeRangeLookbackHours = computed(() => this.selectedRangeConfig().lookbackHours);
  timeRangeGranularity = computed(() => this.selectedRangeConfig().granularity);
  timeRangeLabel = computed(() => RANGE_LABELS[this.selectedTimeRange()] ?? this.selectedTimeRange());

  availableRegistrars = computed(() => this.filterOptions()?.registrars ?? []);
  availableTlds = computed(() => this.filterOptions()?.tlds ?? []);
  hasActiveFilters = computed(
    () => this.selectedRegistrarIds().length > 0 || this.selectedTlds().length > 0
  );

  /** Client-side filtered computeds for non-analytics pages. */
  filteredPortfolio = computed(() => {
    let data = this.portfolio();
    const regIds = this.selectedRegistrarIds();
    const tlds = this.selectedTlds();
    if (regIds.length > 0) data = data.filter(e => regIds.includes(e.registrarId));
    if (tlds.length > 0) data = data.filter(e => e.allowedTlds.some(t => tlds.includes(t)));
    return data;
  });

  filteredPricingRules = computed(() => {
    let data = this.pricingRules();
    const regIds = this.selectedRegistrarIds();
    const tlds = this.selectedTlds();
    if (regIds.length > 0) data = data.filter(r => regIds.includes(r.registrarId));
    if (tlds.length > 0) data = data.filter(r => tlds.includes(r.tld));
    return data;
  });

  filteredEffectiveFees = computed(() => {
    let data = this.effectiveFees();
    const regIds = this.selectedRegistrarIds();
    const tlds = this.selectedTlds();
    if (regIds.length > 0) data = data.filter(f => regIds.includes(f.registrarId));
    if (tlds.length > 0) data = data.filter(f => tlds.includes(f.tld));
    return data;
  });

  filteredTldFees = computed(() => {
    const data = this.tldFees();
    const tlds = this.selectedTlds();
    if (tlds.length === 0) return data;
    return data.filter(f => tlds.includes(f.tld));
  });

  clearFilters() {
    this.selectedRegistrarIds.set([]);
    this.selectedTlds.set([]);
  }

  systemInfo = computed(() => this.adminData()?.systemInfo);

  constructor(private backend: BackendService) {}

  private handleError<T>(err: HttpErrorResponse): Observable<T> {
    let msg: string;
    if (err.error instanceof Error) {
      msg = err.error.message;
    } else if (typeof err.error === 'string') {
      msg = err.error;
    } else if (err.error?.message) {
      msg = err.error.message;
    } else {
      msg = err.message || `Error ${err.status}`;
    }
    this.error.set(msg);
    this.loading.set(false);
    return throwError(() => err);
  }

  /** Returns true if the given column key is visible. Absent key = visible. */
  isColumnVisible(key: string): boolean {
    return this.columnVisibility()[key] !== false;
  }

  getSettings(): Observable<Record<string, any>> {
    return this.backend
      .getRegistryDashSettings()
      .pipe(
        tap((settings) => {
          this.settingsCache.set(settings);
          const cv = settings?.['columnVisibility'] ?? {};
          // Merge: defaults first, then explicit settings override
          this.columnVisibility.set({ ...RegistryDashService.DEFAULT_VISIBILITY, ...cv });
        }),
        catchError((err) => this.handleError<Record<string, any>>(err))
      );
  }

  updateSettings(registryId: number, settings: string): Observable<unknown> {
    return this.backend.updateRegistryDashSettings(registryId, settings).pipe(
      tap(() => {
        try {
          const parsed = JSON.parse(settings);
          this.columnVisibility.set({
            ...RegistryDashService.DEFAULT_VISIBILITY,
            ...(parsed?.['columnVisibility'] ?? {}),
          });
        } catch { /* ignore parse errors */ }
      }),
      catchError((err) => this.handleError<unknown>(err))
    );
  }

  updateSettingsSelf(partialSettings: Record<string, any>): Observable<Record<string, any>> {
    const merged = { ...this.settingsCache(), ...partialSettings };
    return this.backend.updateRegistryDashSettingsSelf(merged).pipe(
      tap((returned) => {
        this.settingsCache.set(returned);
        const cv = returned?.['columnVisibility'] ?? {};
        this.columnVisibility.set({ ...RegistryDashService.DEFAULT_VISIBILITY, ...cv });
      }),
      catchError((err) => this.handleError<Record<string, any>>(err))
    );
  }

  getFilterOptions(): Observable<FilterOptionsData> {
    return this.backend
      .getRegistryDashFilterOptions()
      .pipe(
        tap((data) => this.filterOptions.set(data)),
        catchError((err) => this.handleError<FilterOptionsData>(err))
      );
  }

  getOverview(filterTlds?: string[], filterRegistrarIds?: string[]): Observable<OverviewData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashOverview(filterTlds, filterRegistrarIds)
      .pipe(
        tap((data) => {
          this.overview.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<OverviewData>(err))
      );
  }

  getPortfolio(filterTlds?: string[], filterRegistrarIds?: string[]): Observable<PortfolioEntry[]> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashPortfolio(filterTlds, filterRegistrarIds)
      .pipe(
        tap((data) => {
          this.portfolio.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<PortfolioEntry[]>(err))
      );
  }

  getPricing(): Observable<PricingRule[]> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashPricing()
      .pipe(
        tap((data) => {
          this.pricingRules.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<PricingRule[]>(err))
      );
  }

  createPricingRule(rule: PricingRule): Observable<PricingRule> {
    return this.backend.createRegistryDashPricing(rule).pipe(
      tap((created) => {
        this.pricingRules.update((rules) => [...rules, created]);
      }),
      catchError((err) => this.handleError<PricingRule>(err))
    );
  }

  updatePricingRule(rule: PricingRule): Observable<PricingRule> {
    return this.backend.updateRegistryDashPricing(rule).pipe(
      tap((updated) => {
        this.pricingRules.update((rules) =>
          rules.map((r) => (r.id === updated.id ? updated : r))
        );
      }),
      catchError((err) => this.handleError<PricingRule>(err))
    );
  }

  getCostBasis(): Observable<CostBasisEntry[]> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashCostBasis()
      .pipe(
        tap((data) => {
          this.costBasis.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<CostBasisEntry[]>(err))
      );
  }

  createCostBasis(entry: CostBasisEntry): Observable<CostBasisEntry> {
    return this.backend.createRegistryDashCostBasis(entry).pipe(
      tap((created) => {
        this.costBasis.update((entries) => [...entries, created]);
      }),
      catchError((err) => this.handleError<CostBasisEntry>(err))
    );
  }

  updateCostBasis(entry: CostBasisEntry): Observable<CostBasisEntry> {
    return this.backend.updateRegistryDashCostBasis(entry).pipe(
      tap((updated) => {
        this.costBasis.update((entries) =>
          entries.map((e) => (e.id === updated.id ? updated : e))
        );
      }),
      catchError((err) => this.handleError<CostBasisEntry>(err))
    );
  }

  getAdminData(): Observable<AdminData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashAdmin()
      .pipe(
        tap((data) => {
          this.adminData.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<AdminData>(err))
      );
  }

  adminAction(payload: {
    action: string;
    registryId?: number;
    registryName?: string;
    tld?: string;
    userEmail?: string;
    id?: number;
  }): Observable<unknown> {
    return this.backend.postRegistryDashAdmin(payload).pipe(
      tap(() => {
        this.getAdminData().subscribe();
      }),
      catchError((err) => this.handleError<unknown>(err))
    );
  }

  /** Fetch the per-family list of available Claude models for the chat modal selector. */
  getAiModelCatalog(): Observable<AiModelCatalogResponse> {
    return this.backend
      .getRegistryDashAiCatalog()
      .pipe(catchError((err) => this.handleError<AiModelCatalogResponse>(err)));
  }

  getRevenueBilling(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): Observable<RevenueBillingData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashRevenueBilling(lookbackHours, granularity, filterTlds, filterRegistrarIds)
      .pipe(
        tap((data) => {
          this.revenueBilling.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<RevenueBillingData>(err))
      );
  }

  getDomainActivity(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): Observable<DomainActivityData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashDomainActivity(lookbackHours, granularity, filterTlds, filterRegistrarIds)
      .pipe(
        tap((data) => {
          this.domainActivity.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<DomainActivityData>(err))
      );
  }

  getForecasting(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): Observable<ForecastingData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashForecasting(lookbackHours, granularity, filterTlds, filterRegistrarIds)
      .pipe(
        tap((data) => {
          this.forecasting.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<ForecastingData>(err))
      );
  }

  getTldFees(): Observable<TldFeeEntry[]> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashTldFees()
      .pipe(
        tap((data) => {
          this.tldFees.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<TldFeeEntry[]>(err))
      );
  }

  getEffectiveFees(): Observable<EffectiveFeeEntry[]> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashEffectiveFees()
      .pipe(
        tap((data) => {
          this.effectiveFees.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<EffectiveFeeEntry[]>(err))
      );
  }
}
