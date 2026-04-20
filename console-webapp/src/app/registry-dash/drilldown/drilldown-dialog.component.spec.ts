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
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { DrillDownDialogComponent } from './drilldown-dialog.component';
import { DrillDownDialogData } from './drilldown.models';

describe('DrillDownDialogComponent', () => {
  let component: DrillDownDialogComponent;
  let fixture: ComponentFixture<DrillDownDialogComponent>;

  const mockData: DrillDownDialogData = {
    title: 'Test Dialog',
    subtitle: 'Test subtitle',
    columns: [
      { key: 'name', label: 'Name' },
      { key: 'count', label: 'Count', format: 'number' },
      { key: 'revenue', label: 'Revenue', format: 'currency' },
      { key: 'rate', label: 'Rate', format: 'percent' },
    ],
    rows: [
      { name: '.modem', count: 100, revenue: 1234.5, rate: 90.5 },
      { name: '.nft', count: 50, revenue: 678.9, rate: 70.2 },
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DrillDownDialogComponent, BrowserAnimationsModule],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: mockData },
        { provide: MatDialogRef, useValue: { close: jasmine.createSpy() } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DrillDownDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('creates the component', () => {
    expect(component).toBeTruthy();
  });

  it('displays the title', () => {
    const title = fixture.nativeElement.querySelector('[mat-dialog-title]');
    expect(title.textContent).toContain('Test Dialog');
  });

  it('displays the subtitle', () => {
    const subtitle = fixture.nativeElement.querySelector('.dialog-subtitle');
    expect(subtitle.textContent).toContain('Test subtitle');
  });

  it('renders table with correct columns', () => {
    const headers = fixture.nativeElement.querySelectorAll('th');
    expect(headers.length).toBe(4);
    expect(headers[0].textContent).toContain('Name');
    expect(headers[1].textContent).toContain('Count');
  });

  it('renders table with correct row count', () => {
    const rows = fixture.nativeElement.querySelectorAll('tr.mat-mdc-row');
    expect(rows.length).toBe(2);
  });

  it('formats currency values', () => {
    expect(component.formatValue(1234.5, 'currency')).toBe('$1,234.50');
  });

  it('formats percent values', () => {
    expect(component.formatValue(90.5, 'percent')).toBe('90.5%');
  });

  it('formats number values', () => {
    expect(component.formatValue(1000, 'number')).toBe('1,000');
  });

  it('handles null values', () => {
    expect(component.formatValue(null)).toBe('—');
    expect(component.formatValue(undefined)).toBe('—');
  });
});
