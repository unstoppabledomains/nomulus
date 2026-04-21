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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { RevenueBillingComponent, RANGE_CONFIG } from './revenue-billing.component';
import { RegistryDashService, RevenueBillingData } from '../../registry-dash.service';
import { NgxEchartsDirective } from 'ngx-echarts';
import { LongPressDirective } from '../../drilldown/long-press.directive';
import { BackendService } from '../../../shared/services/backend.service';

describe('RevenueBillingComponent', () => {
  let component: RevenueBillingComponent;
  let fixture: ComponentFixture<RevenueBillingComponent>;
  let mockDashService: jasmine.SpyObj<RegistryDashService>;

  const mockData: RevenueBillingData = {
    periodRevenue: [
      { period: '2024-05', tld: 'tld', operation: 'CREATE', amount: 100, netAmountToRegistry: 60, currency: 'USD' },
      { period: '2024-05', tld: 'tld2', operation: 'CREATE', amount: 200, netAmountToRegistry: 140, currency: 'USD' },
      { period: '2024-06', tld: 'tld', operation: 'RENEW', amount: 50, netAmountToRegistry: 30, currency: 'USD' },
    ],
    totals: {
      totalRevenue: 350,
      totalNetAmountToRegistry: 230,
      currency: 'USD',
      byOperation: { CREATE: 300, RENEW: 50 },
      byOperationNetAmountToRegistry: { CREATE: 200, RENEW: 30 },
    },
  };

  beforeEach(async () => {
    mockDashService = jasmine.createSpyObj('RegistryDashService', ['getRevenueBilling'], {
      revenueBilling: signal<RevenueBillingData | undefined>(mockData),
      loading: signal(false),
      error: signal<string | undefined>(undefined),
      selectedTlds: signal<string[]>([]),
      selectedRegistrarIds: signal<string[]>([]),
    });
    mockDashService.getRevenueBilling.and.returnValue(of(mockData));

    await TestBed.configureTestingModule({
      imports: [RevenueBillingComponent, NoopAnimationsModule],
      providers: [
        { provide: RegistryDashService, useValue: mockDashService },
        { provide: BackendService, useValue: jasmine.createSpyObj('BackendService', ['getRegistryDashRegistrarDetail']) },
      ],
    })
      .overrideComponent(RevenueBillingComponent, {
        remove: { imports: [NgxEchartsDirective, LongPressDirective], providers: [] },
      })
      .compileComponents();

    fixture = TestBed.createComponent(RevenueBillingComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // --- Legend fix tests ---

  it('should use scrollable legend type', () => {
    fixture.detectChanges();
    const options = component.revenueLineOptions();
    expect(options).toBeTruthy();
    expect(options!.legend.type).toBe('scroll');
    expect(options!.legend.bottom).toBe(0);
  });

  // --- Range selector tests ---

  it('should default to 12m range', () => {
    expect(component.selectedRange()).toBe('12m');
  });

  it('should have 9 range options in the config', () => {
    expect(Object.keys(RANGE_CONFIG)).toEqual([
      '6h', '12h', '1d', '7d', '30d', '3m', '6m', '12m', '24m',
    ]);
  });

  it('should call getRevenueBilling with lookbackHours and granularity on range change', () => {
    fixture.detectChanges();
    mockDashService.getRevenueBilling.calls.reset();
    component.onRangeChange('7d');
    fixture.detectChanges();
    expect(mockDashService.getRevenueBilling).toHaveBeenCalledWith(168, 'day', undefined, undefined);
  });

  it('should call getRevenueBilling with correct params for 6h range', () => {
    fixture.detectChanges();
    mockDashService.getRevenueBilling.calls.reset();
    component.onRangeChange('6h');
    fixture.detectChanges();
    expect(mockDashService.getRevenueBilling).toHaveBeenCalledWith(6, '15min', undefined, undefined);
  });

  it('should call getRevenueBilling with correct params for 12m range', () => {
    fixture.detectChanges();
    // 12m is the default, so the effect already fired with these params
    expect(mockDashService.getRevenueBilling).toHaveBeenCalledWith(8760, 'month', undefined, undefined);
  });

  // --- Chart data tests ---

  it('should compute total net registry revenue from data', () => {
    fixture.detectChanges();
    expect(component.totalNetAmountToRegistry()).toBe(230);
  });

  it('should compute top TLD by net registry revenue', () => {
    fixture.detectChanges();
    expect(component.topTld()).toBe('tld2');
  });

  // --- Operation bar chart ---

  it('should generate operation bar chart options using net registry revenue', () => {
    fixture.detectChanges();
    const options = component.operationBarOptions();
    expect(options).toBeTruthy();
    expect(options!.yAxis.data).toContain('CREATE');
    expect(options!.yAxis.data).toContain('RENEW');
  });
});
