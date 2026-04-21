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

import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { signal } from '@angular/core';
import { DrillDownService } from './drilldown.service';
import { RegistryDashService } from '../registry-dash.service';
import { BackendService } from '../../shared/services/backend.service';

describe('DrillDownService', () => {
  let service: DrillDownService;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let dashServiceStub: any;
  let backendSpy: jasmine.SpyObj<BackendService>;

  beforeEach(() => {
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    backendSpy = jasmine.createSpyObj('BackendService', ['getRegistryDashRegistrarDetail']);
    dashServiceStub = {
      selectedTlds: signal<string[]>([]),
      selectedRegistrarIds: signal<string[]>([]),
      domainActivity: signal({
        activity: [
          { period: '2025-01', tld: 'modem', type: 'CREATES', count: 100 },
          { period: '2025-01', tld: 'modem', type: 'DELETES', count: 20 },
          { period: '2025-01', tld: 'nft', type: 'CREATES', count: 50 },
          { period: '2025-02', tld: 'modem', type: 'CREATES', count: 80 },
        ],
        currentCounts: { modem: 500, nft: 200 },
      }),
      revenueBilling: signal({
        periodRevenue: [
          { period: '2025-01', tld: 'modem', operation: 'CREATE', amount: 1000, netAmountToRegistry: 800, currency: 'USD' },
          { period: '2025-01', tld: 'modem', operation: 'RENEW', amount: 500, netAmountToRegistry: 400, currency: 'USD' },
          { period: '2025-01', tld: 'nft', operation: 'CREATE', amount: 300, netAmountToRegistry: 240, currency: 'USD' },
        ],
        totals: { totalRevenue: 1800, totalNetAmountToRegistry: 1440, currency: 'USD', byOperation: {}, byOperationNetAmountToRegistry: {} },
      }),
      forecasting: signal({
        renewalRates: [
          { tld: 'modem', renewals: 90, deletions: 10, renewalRate: 90.0 },
          { tld: 'nft', renewals: 70, deletions: 30, renewalRate: 70.0 },
        ],
        expirationCurve: [
          { month: '2025-06', tld: 'modem', count: 50 },
          { month: '2025-07', tld: 'modem', count: 60 },
        ],
      }),
      tldFees: signal([
        { tld: 'modem', operation: 'CREATE', defaultPrice: 10, currency: 'USD' },
        { tld: 'modem', operation: 'RENEW', defaultPrice: 8, currency: 'USD' },
      ]),
      portfolio: signal([]),
    };

    TestBed.configureTestingModule({
      providers: [
        DrillDownService,
        { provide: MatDialog, useValue: dialogSpy },
        { provide: RegistryDashService, useValue: dashServiceStub },
        { provide: BackendService, useValue: backendSpy },
      ],
    });
    service = TestBed.inject(DrillDownService);
  });

  it('applyTldFilter sets selectedTlds', () => {
    service.applyTldFilter('.modem');
    expect(dashServiceStub.selectedTlds()).toEqual(['modem']);
  });

  it('applyTldFilter strips leading dot', () => {
    service.applyTldFilter('.nft');
    expect(dashServiceStub.selectedTlds()).toEqual(['nft']);
  });

  it('applyRegistrarFilter sets selectedRegistrarIds', () => {
    service.applyRegistrarFilter('reg1');
    expect(dashServiceStub.selectedRegistrarIds()).toEqual(['reg1']);
  });

  it('drillDownActivityByPeriod opens dialog with TLD breakdown', () => {
    service.drillDownActivityByPeriod('2025-01');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Activity Breakdown: 2025-01');
    expect(data.rows.length).toBe(2);
    expect(data.rows[0].tld).toBe('.modem');
  });

  it('drillDownActivityByTld opens dialog with time-series', () => {
    service.drillDownActivityByTld('.modem');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Activity: .modem');
    expect(data.rows.length).toBe(2);
  });

  it('drillDownRevenueByTld opens dialog with operation breakdown', () => {
    service.drillDownRevenueByTld('modem');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Revenue: .modem');
    expect(data.rows.length).toBe(2);
  });

  it('drillDownRevenueByOperation opens dialog with TLD breakdown', () => {
    service.drillDownRevenueByOperation('CREATE');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Revenue by TLD: CREATE');
    expect(data.rows.length).toBe(2);
  });

  it('drillDownRenewalByTld opens dialog with renewal stats', () => {
    service.drillDownRenewalByTld('.modem');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Renewal Rate: .modem');
    expect(data.rows[0].value).toBe(90);
  });

  it('drillDownExpirationByTld opens dialog with monthly data', () => {
    service.drillDownExpirationByTld('.modem');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Expirations: .modem');
    expect(data.rows.length).toBe(2);
  });

  it('drillDownNetGrowthByPeriod shows creates vs deletes', () => {
    service.drillDownNetGrowthByPeriod('2025-01');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Net Growth: 2025-01');
    expect(data.rows[0].value).toBe(150);
  });

  it('drillDownFeesByTld opens dialog with fees', () => {
    service.drillDownFeesByTld('.modem');
    expect(dialogSpy.open).toHaveBeenCalledTimes(1);
    const data = dialogSpy.open.calls.first().args[1]!.data as any;
    expect(data.title).toBe('Fees: .modem');
    expect(data.rows.length).toBe(2);
  });

  it('does nothing when data is undefined', () => {
    dashServiceStub.domainActivity.set(undefined);
    service.drillDownActivityByPeriod('2025-01');
    expect(dialogSpy.open).not.toHaveBeenCalled();
  });
});
