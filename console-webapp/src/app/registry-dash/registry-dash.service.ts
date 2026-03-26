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
import { Injectable, computed, signal } from '@angular/core';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { BackendService } from '../shared/services/backend.service';

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

export interface CostBasisEntry {
  id?: number;
  tld: string;
  operation: string;
  registrarId?: string;
  costAmount: number;
  costCurrency: string;
  effectiveDate: string;
  notes?: string;
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
}

export interface RevenueDataPoint {
  month: string;
  tld: string;
  operation: string;
  amount: number;
  currency: string;
}

export interface RevenueTotals {
  totalRevenue: number;
  currency: string;
  byOperation: Record<string, number>;
}

export interface RevenueBillingData {
  monthlyRevenue: RevenueDataPoint[];
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
  loading = signal(false);
  error = signal<string | undefined>(undefined);

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

  getOverview(): Observable<OverviewData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashOverview()
      .pipe(
        tap((data) => {
          this.overview.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<OverviewData>(err))
      );
  }

  getPortfolio(): Observable<PortfolioEntry[]> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashPortfolio()
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

  getRevenueBilling(months?: number): Observable<RevenueBillingData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashRevenueBilling(months)
      .pipe(
        tap((data) => {
          this.revenueBilling.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<RevenueBillingData>(err))
      );
  }

  getDomainActivity(months?: number, granularity?: string): Observable<DomainActivityData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashDomainActivity(months, granularity)
      .pipe(
        tap((data) => {
          this.domainActivity.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<DomainActivityData>(err))
      );
  }

  getForecasting(months?: number): Observable<ForecastingData> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend
      .getRegistryDashForecasting(months)
      .pipe(
        tap((data) => {
          this.forecasting.set(data);
          this.loading.set(false);
        }),
        catchError((err) => this.handleError<ForecastingData>(err))
      );
  }
}
