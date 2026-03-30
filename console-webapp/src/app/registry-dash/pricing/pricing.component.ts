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

import { AfterViewInit, Component, ViewChild, computed, effect, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { SnackBarModule } from '../../snackbar.module';
import { PricingRule, RegistryDashService, SystemRegistrar } from '../registry-dash.service';

@Component({
  selector: 'app-registry-dash-pricing',
  imports: [MaterialModule, CommonModule, ReactiveFormsModule, SnackBarModule, MatSortModule],
  templateUrl: './pricing.component.html',
  styleUrls: ['./pricing.component.scss'],
})
export class PricingComponent implements AfterViewInit {
  private static readonly MONEY_COLUMN_KEYS: Record<string, string> = {
    priceAmount: 'pricing.priceAmount',
    defaultPrice: 'pricing.defaultPrice',
    difference: 'pricing.difference',
  };

  private static readonly ALL_COLUMNS = [
    'registrarId', 'tld', 'operation', 'priceAmount', 'defaultPrice',
    'difference', 'priceCurrency', 'effectiveDate', 'expiryDate', 'isActive', 'actions',
  ];

  displayedColumns = computed(() => {
    return PricingComponent.ALL_COLUMNS.filter(col => {
      const key = PricingComponent.MONEY_COLUMN_KEYS[col];
      return !key || this.dashService.isColumnVisible(key);
    });
  });

  @ViewChild(MatSort) sort!: MatSort;
  dataSource = new MatTableDataSource<PricingRule>([]);

  filterRegistrar = signal<string>('all');
  filterTld = signal<string>('all');

  uniqueRegistrars = computed(() => {
    const rules = this.dashService.pricingRules();
    return [...new Set(rules.map(r => r.registrarId))].sort();
  });

  uniqueTlds = computed(() => {
    const rules = this.dashService.pricingRules();
    return [...new Set(rules.map(r => r.tld))].sort();
  });

  filteredRules = computed(() => {
    let rules = this.dashService.pricingRules();
    const reg = this.filterRegistrar();
    const tld = this.filterTld();
    if (reg !== 'all') rules = rules.filter(r => r.registrarId === reg);
    if (tld !== 'all') rules = rules.filter(r => r.tld === tld);
    return rules;
  });

  showForm = signal(false);
  editingRule = signal<PricingRule | undefined>(undefined);
  selectedRegistrarId = signal<string>('');

  registrars = computed<SystemRegistrar[]>(
    () => this.dashService.systemInfo()?.registrars || []
  );

  availableTlds = computed<string[]>(() => {
    const regId = this.selectedRegistrarId();
    if (!regId) return [];
    const registrar = this.registrars().find((r) => r.registrarId === regId);
    return registrar?.allowedTlds || [];
  });

  pricingForm = new FormGroup({
    registrarId: new FormControl('', [Validators.required]),
    tld: new FormControl('', [Validators.required]),
    operation: new FormControl('', [Validators.required]),
    priceAmount: new FormControl<number | null>(null, [
      Validators.required,
      Validators.min(0),
    ]),
    priceCurrency: new FormControl('USD', [Validators.required]),
    effectiveDate: new FormControl<Date | null>(null),
    expiryDate: new FormControl<Date | null>(null),
    isActive: new FormControl(true),
  });

  constructor(
    protected dashService: RegistryDashService,
    private snackBar: MatSnackBar
  ) {
    this.dashService.getPricing().subscribe();
    // Load admin data to get registrar list with allowedTlds
    this.dashService.getAdminData().subscribe();

    // When registrar selection changes, update the signal and reset TLD
    this.pricingForm.get('registrarId')!.valueChanges.subscribe((value) => {
      this.selectedRegistrarId.set(value || '');
      this.pricingForm.get('tld')!.setValue('');
    });

    // Sync filtered rules into dataSource
    effect(() => {
      this.dataSource.data = this.filteredRules();
    });
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.sort;
    this.dataSource.sortingDataAccessor = (item: PricingRule, property: string) => {
      if (property === 'difference') {
        return this.getDifference(item) ?? 0;
      }
      if (property === 'defaultPrice') {
        return item.defaultPrice ?? 0;
      }
      return (item as any)[property];
    };
  }

  getDifference(rule: PricingRule): number | null {
    if (rule.defaultPrice == null) return null;
    return rule.priceAmount - rule.defaultPrice;
  }

  getDifferenceClass(rule: PricingRule): string {
    const diff = this.getDifference(rule);
    if (diff === null) return '';
    if (diff < 0) return 'diff-discount';
    if (diff > 0) return 'diff-premium';
    return 'diff-zero';
  }

  clearFilters() {
    this.filterRegistrar.set('all');
    this.filterTld.set('all');
  }

  openAddForm() {
    this.editingRule.set(undefined);
    this.pricingForm.reset({ priceCurrency: 'USD', isActive: true });
    this.showForm.set(true);
  }

  openEditForm(rule: PricingRule) {
    this.editingRule.set(rule);
    this.selectedRegistrarId.set(rule.registrarId);
    this.pricingForm.patchValue({
      registrarId: rule.registrarId,
      tld: rule.tld,
      operation: rule.operation,
      priceAmount: rule.priceAmount,
      priceCurrency: rule.priceCurrency,
      effectiveDate: rule.effectiveDate ? new Date(rule.effectiveDate) : null,
      expiryDate: rule.expiryDate ? new Date(rule.expiryDate) : null,
      isActive: rule.isActive,
    });
    this.showForm.set(true);
  }

  cancelForm() {
    this.showForm.set(false);
    this.editingRule.set(undefined);
  }

  onSubmit() {
    if (!this.pricingForm.valid) return;

    const formValue = this.pricingForm.value;
    const rule: PricingRule = {
      registrarId: formValue.registrarId!,
      tld: formValue.tld!,
      operation: formValue.operation!,
      priceAmount: formValue.priceAmount!,
      priceCurrency: formValue.priceCurrency!,
      effectiveDate: formValue.effectiveDate instanceof Date ? formValue.effectiveDate.toISOString() : '',
      expiryDate: formValue.expiryDate instanceof Date ? formValue.expiryDate.toISOString() : undefined,
      isActive: formValue.isActive ?? true,
    };

    const editing = this.editingRule();
    if (editing?.id) {
      rule.id = editing.id;
      this.dashService.updatePricingRule(rule).subscribe({
        next: () => {
          this.snackBar.open('Pricing rule updated', 'OK', { duration: 3000 });
          this.cancelForm();
        },
        error: (err) => this.snackBar.open(err.error || 'Update failed'),
      });
    } else {
      this.dashService.createPricingRule(rule).subscribe({
        next: () => {
          this.snackBar.open('Pricing rule created', 'OK', { duration: 3000 });
          this.cancelForm();
        },
        error: (err) => this.snackBar.open(err.error || 'Create failed'),
      });
    }
  }

}
