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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';

import { DomainListResult } from 'src/app/domains/domainList.service';
import { DomainLocksResult } from 'src/app/domains/registryLock.service';
import { RegistryLockVerificationResponse } from 'src/app/lock/registryLockVerify.service';
import { OteCreateResponse } from 'src/app/ote/newOte.component';
import { OteStatusResponse } from 'src/app/ote/oteStatus.component';
import {
  OverviewData,
  PortfolioEntry,
  PricingRule,
  CostBasisEntry,
  AdminData,
  RevenueBillingData,
  DomainActivityData,
  ForecastingData,
  TldFeeEntry,
  EffectiveFeeEntry,
  FilterOptionsData,
} from 'src/app/registry-dash/registry-dash.service';
import { User } from 'src/app/users/users.service';
import {
  Registrar,
  SecuritySettingsBackendModel,
  RdapRegistrarFields,
} from '../../registrar/registrar.service';
import { Contact } from '../../settings/contact/contact.service';
import { EppPasswordBackendModel } from '../../settings/security/security.service';
import { UserData } from './userData.service';
import { PasswordResetVerifyResponse } from '../components/passwordReset/passwordResetVerify.component';
import { HistoryRecord } from '../../history/history.service';

@Injectable()
export class BackendService {
  constructor(private http: HttpClient) {}

  errorCatcher<Type>(
    error: HttpErrorResponse,
    mockData?: Type
  ): Observable<Type> {
    // This is a temporary redirect to the old console until the new console
    // is fully released and enabled
    if (error.url && new URL(error.url).pathname === '/registrar') {
      window.location.href = error.url;
    }
    if (error.error instanceof Error) {
      // A client-side or network error occurred. Handle it accordingly.
      console.error('An error occurred:', error.error.message);
    } else {
      // The backend returned an unsuccessful response code.
      // The response body may contain clues as to what went wrong,
      console.error(
        `Backend returned code ${error.status}, body was: ${error.error}`
      );
    }

    if (mockData) {
      return of(<Type>mockData);
    } else {
      return throwError(() => error);
    }
  }

  getContacts(registrarId: string): Observable<Contact[]> {
    return this.http
      .get<Contact[]>(
        `/console-api/settings/contacts?registrarId=${registrarId}`
      )
      .pipe(catchError((err) => this.errorCatcher<Contact[]>(err)));
  }

  updateContact(registrarId: string, contact: Contact): Observable<Contact> {
    return this.http.put<Contact>(
      `/console-api/settings/contacts?registrarId=${registrarId}`,
      contact
    );
  }

  createContact(registrarId: string, contact: Contact): Observable<Contact> {
    return this.http.post<Contact>(
      `/console-api/settings/contacts?registrarId=${registrarId}`,
      contact
    );
  }

  deleteContact(registrarId: string, contact: Contact): Observable<Contact> {
    return this.http.delete<Contact>(
      `/console-api/settings/contacts?registrarId=${registrarId}`,
      {
        body: JSON.stringify(contact),
      }
    );
  }

  getDomains(
    registrarId: string,
    checkpointTime?: string,
    pageNumber?: number,
    resultsPerPage?: number,
    totalResults?: number,
    searchTerm?: string
  ): Observable<DomainListResult> {
    var url = `/console-api/domain-list?registrarId=${registrarId}`;
    if (checkpointTime) {
      url += `&checkpointTime=${checkpointTime}`;
    }
    if (pageNumber) {
      url += `&pageNumber=${pageNumber}`;
    }
    if (resultsPerPage) {
      url += `&resultsPerPage=${resultsPerPage}`;
    }
    if (totalResults) {
      url += `&totalResults=${totalResults}`;
    }
    if (searchTerm) {
      url += `&searchTerm=${searchTerm}`;
    }
    return this.http
      .get<DomainListResult>(url)
      .pipe(catchError((err) => this.errorCatcher<DomainListResult>(err)));
  }

  getHistoryLog(registrarId: string, userEmail?: string) {
    return this.http
      .get<HistoryRecord[]>(
        userEmail
          ? `/console-api/history?registrarId=${registrarId}&consoleUserEmail=${userEmail}`
          : `/console-api/history?registrarId=${registrarId}`
      )
      .pipe(catchError((err) => this.errorCatcher<HistoryRecord[]>(err)));
  }

  // UD: Registry Dashboard — return empty array for users without registrar access
  // (e.g. REGISTRY_OPERATOR) so the app doesn't hang on startup
  getRegistrars(): Observable<Registrar[]> {
    return this.http
      .get<Registrar[]>('/console-api/registrars')
      .pipe(catchError((err) => this.errorCatcher<Registrar[]>(err, [])));
  }

  createRegistrar(registrar: Registrar): Observable<Registrar> {
    return this.http
      .post<Registrar>('/console-api/registrars', registrar)
      .pipe(catchError((err) => this.errorCatcher<Registrar>(err)));
  }

  updateRegistrar(registrar: Registrar): Observable<Registrar> {
    return this.http
      .post<Registrar>('/console-api/registrar', registrar)
      .pipe(catchError((err) => this.errorCatcher<Registrar>(err)));
  }

  getSecuritySettings(
    registrarId: string
  ): Observable<SecuritySettingsBackendModel> {
    return this.http
      .get<SecuritySettingsBackendModel>(
        `/console-api/settings/security?registrarId=${registrarId}`
      )
      .pipe(
        catchError((err) =>
          this.errorCatcher<SecuritySettingsBackendModel>(err)
        )
      );
  }

  postSecuritySettings(
    registrarId: string,
    securitySettings: SecuritySettingsBackendModel
  ): Observable<SecuritySettingsBackendModel> {
    return this.http.post<SecuritySettingsBackendModel>(
      `/console-api/settings/security?registrarId=${registrarId}`,
      securitySettings
    );
  }

  postEppPasswordUpdate(
    data: EppPasswordBackendModel
  ): Observable<EppPasswordBackendModel> {
    return this.http.post<EppPasswordBackendModel>(
      `/console-api/eppPassword`,
      data
    );
  }

  getUsers(registrarId: string): Observable<User[]> {
    return this.http
      .get<User[]>(`/console-api/users?registrarId=${registrarId}`)
      .pipe(catchError((err) => this.errorCatcher<User[]>(err)));
  }

  createUser(registrarId: string, maybeUser: User | null): Observable<User> {
    return this.http
      .post<User>(`/console-api/users?registrarId=${registrarId}`, maybeUser)
      .pipe(catchError((err) => this.errorCatcher<User>(err)));
  }

  deleteUser(registrarId: string, user: User): Observable<any> {
    return this.http
      .delete<any>(`/console-api/users?registrarId=${registrarId}`, {
        body: JSON.stringify(user),
      })
      .pipe(catchError((err) => this.errorCatcher<any>(err)));
  }

  bulkDomainAction(
    domainNames: string[],
    reason: string,
    bulkDomainAction: string,
    registrarId: string
  ) {
    return this.http
      .post<any>(
        `/console-api/bulk-domain?registrarId=${registrarId}&bulkDomainAction=${bulkDomainAction}`,
        {
          domainList: domainNames,
          reason,
        }
      )
      .pipe(catchError((err) => this.errorCatcher<any>(err)));
  }

  updateUser(registrarId: string, updatedUser: User): Observable<any> {
    return this.http
      .put<User>(`/console-api/users?registrarId=${registrarId}`, updatedUser)
      .pipe(catchError((err) => this.errorCatcher<any>(err)));
  }

  getUserData(): Observable<UserData> {
    return this.http
      .get<UserData>('/console-api/userdata')
      .pipe(catchError((err) => this.errorCatcher<UserData>(err)));
  }

  postRdapRegistrarFields(
    rdapRegistrarFields: RdapRegistrarFields
  ): Observable<RdapRegistrarFields> {
    return this.http.post<RdapRegistrarFields>(
      '/console-api/settings/rdap-fields',
      rdapRegistrarFields
    );
  }

  registryLockDomain(
    domainName: string,
    password: string | undefined,
    relockDurationMillis: number | undefined,
    registrarId: string,
    isLock: boolean
  ) {
    return this.http.post(
      `/console-api/registry-lock?registrarId=${registrarId}`,
      {
        domainName,
        password,
        isLock,
        relockDurationMillis,
      }
    );
  }

  getLocks(registrarId: string): Observable<DomainLocksResult[]> {
    return this.http
      .get<DomainLocksResult[]>(
        `/console-api/registry-lock?registrarId=${registrarId}`
      )
      .pipe(catchError((err) => this.errorCatcher<DomainLocksResult[]>(err)));
  }

  generateOte(
    oteForm: Object,
    registrarId: string
  ): Observable<OteCreateResponse> {
    return this.http.post<OteCreateResponse>(
      `/console-api/ote?registrarId=${registrarId}`,
      oteForm
    );
  }

  getOteStatus(registrarId: string) {
    return this.http
      .get<OteStatusResponse[]>(`/console-api/ote?registrarId=${registrarId}`)
      .pipe(catchError((err) => this.errorCatcher<OteStatusResponse[]>(err)));
  }

  verifyRegistryLockRequest(
    lockVerificationCode: string
  ): Observable<RegistryLockVerificationResponse> {
    return this.http.get<RegistryLockVerificationResponse>(
      `/console-api/registry-lock-verify?lockVerificationCode=${lockVerificationCode}`
    );
  }

  requestRegistryLockPasswordReset(
    registrarId: string,
    registryLockEmail: string
  ) {
    return this.http.post('/console-api/password-reset-request', {
      type: 'REGISTRY_LOCK',
      registrarId,
      registryLockEmail,
    });
  }

  requestEppPasswordReset(registrarId: string) {
    return this.http.post('/console-api/password-reset-request', {
      type: 'EPP',
      registrarId,
    });
  }

  getPasswordResetInformation(
    verificationCode: string
  ): Observable<PasswordResetVerifyResponse> {
    return this.http.get<PasswordResetVerifyResponse>(
      `/console-api/password-reset-verify?resetRequestVerificationCode=${verificationCode}`
    );
  }

  finalizePasswordReset(verificationCode: string, newPassword: string) {
    return this.http.post(
      `/console-api/password-reset-verify?resetRequestVerificationCode=${verificationCode}`,
      newPassword
    );
  }

  // --- Registry Dashboard ---

  getRegistryDashFilterOptions(): Observable<FilterOptionsData> {
    return this.http
      .get<FilterOptionsData>('/console-api/registry-dash/filter-options')
      .pipe(catchError((err) => this.errorCatcher<FilterOptionsData>(err)));
  }

  getRegistryDashOverview(filterTlds?: string[], filterRegistrarIds?: string[]): Observable<OverviewData> {
    const params = this.buildFilterParams(undefined, undefined, filterTlds, filterRegistrarIds);
    return this.http
      .get<OverviewData>(`/console-api/registry-dash/overview${params}`)
      .pipe(catchError((err) => this.errorCatcher<OverviewData>(err)));
  }

  getRegistryDashPortfolio(filterTlds?: string[], filterRegistrarIds?: string[]): Observable<PortfolioEntry[]> {
    const params = this.buildFilterParams(undefined, undefined, filterTlds, filterRegistrarIds);
    return this.http
      .get<PortfolioEntry[]>(`/console-api/registry-dash/portfolio${params}`)
      .pipe(catchError((err) => this.errorCatcher<PortfolioEntry[]>(err)));
  }

  getRegistryDashPricing(): Observable<PricingRule[]> {
    return this.http
      .get<PricingRule[]>('/console-api/registry-dash/pricing')
      .pipe(catchError((err) => this.errorCatcher<PricingRule[]>(err)));
  }

  createRegistryDashPricing(rule: PricingRule): Observable<PricingRule> {
    return this.http.post<PricingRule>(
      '/console-api/registry-dash/pricing',
      rule
    );
  }

  updateRegistryDashPricing(rule: PricingRule): Observable<PricingRule> {
    return this.http.put<PricingRule>(
      '/console-api/registry-dash/pricing',
      rule
    );
  }

  getRegistryDashCostBasis(): Observable<CostBasisEntry[]> {
    return this.http
      .get<CostBasisEntry[]>('/console-api/registry-dash/cost-basis')
      .pipe(catchError((err) => this.errorCatcher<CostBasisEntry[]>(err)));
  }

  createRegistryDashCostBasis(
    entry: CostBasisEntry
  ): Observable<CostBasisEntry> {
    return this.http.post<CostBasisEntry>(
      '/console-api/registry-dash/cost-basis',
      entry
    );
  }

  updateRegistryDashCostBasis(
    entry: CostBasisEntry
  ): Observable<CostBasisEntry> {
    return this.http.put<CostBasisEntry>(
      '/console-api/registry-dash/cost-basis',
      entry
    );
  }

  // --- Registry Dashboard Admin ---

  getRegistryDashAdmin(): Observable<AdminData> {
    return this.http
      .get<AdminData>('/console-api/registry-dash/admin')
      .pipe(catchError((err) => this.errorCatcher<AdminData>(err)));
  }

  postRegistryDashAdmin(payload: unknown): Observable<unknown> {
    return this.http.post('/console-api/registry-dash/admin', payload);
  }

  // --- Registry Dashboard Settings ---

  getRegistryDashSettings(): Observable<Record<string, any>> {
    return this.http
      .get<Record<string, any>>('/console-api/registry-dash/settings')
      .pipe(catchError((err) => this.errorCatcher<Record<string, any>>(err)));
  }

  updateRegistryDashSettings(registryId: number, settings: string): Observable<unknown> {
    return this.http.post('/console-api/registry-dash/admin', {
      action: 'updateSettings',
      registryId,
      settings,
    });
  }

  // --- Registry Dashboard Analytics ---

  getRegistryDashRevenueBilling(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): Observable<RevenueBillingData> {
    const params = this.buildFilterParams(lookbackHours, granularity, filterTlds, filterRegistrarIds);
    return this.http
      .get<RevenueBillingData>(`/console-api/registry-dash/revenue-billing${params}`)
      .pipe(catchError((err) => this.errorCatcher<RevenueBillingData>(err)));
  }

  getRegistryDashDomainActivity(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): Observable<DomainActivityData> {
    const params = this.buildFilterParams(lookbackHours, granularity, filterTlds, filterRegistrarIds);
    return this.http
      .get<DomainActivityData>(`/console-api/registry-dash/domain-activity${params}`)
      .pipe(catchError((err) => this.errorCatcher<DomainActivityData>(err)));
  }

  getRegistryDashForecasting(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): Observable<ForecastingData> {
    const params = this.buildFilterParams(lookbackHours, granularity, filterTlds, filterRegistrarIds);
    return this.http
      .get<ForecastingData>(`/console-api/registry-dash/forecasting${params}`)
      .pipe(catchError((err) => this.errorCatcher<ForecastingData>(err)));
  }

  /** Builds query string for analytics endpoints with optional time + filter params. */
  private buildFilterParams(
    lookbackHours?: number, granularity?: string,
    filterTlds?: string[], filterRegistrarIds?: string[]
  ): string {
    const parts: string[] = [];
    if (lookbackHours) parts.push(`lookbackHours=${lookbackHours}`);
    if (granularity) parts.push(`granularity=${granularity}`);
    if (filterTlds?.length) parts.push(`filterTlds=${filterTlds.join(',')}`);
    if (filterRegistrarIds?.length) parts.push(`filterRegistrarIds=${filterRegistrarIds.join(',')}`);
    return parts.length > 0 ? `?${parts.join('&')}` : '';
  }

  getRegistryDashTldFees(): Observable<TldFeeEntry[]> {
    return this.http
      .get<TldFeeEntry[]>('/console-api/registry-dash/tld-fees')
      .pipe(catchError((err) => this.errorCatcher<TldFeeEntry[]>(err)));
  }

  getRegistryDashEffectiveFees(): Observable<EffectiveFeeEntry[]> {
    return this.http
      .get<EffectiveFeeEntry[]>('/console-api/registry-dash/effective-fees')
      .pipe(catchError((err) => this.errorCatcher<EffectiveFeeEntry[]>(err)));
  }
}
