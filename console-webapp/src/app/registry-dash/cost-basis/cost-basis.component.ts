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

import { Component, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SnackBarModule } from '../../snackbar.module';
import { CostBasisEntry, RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-registry-dash-cost-basis',
  imports: [MaterialModule, CommonModule, ReactiveFormsModule, SnackBarModule],
  templateUrl: './cost-basis.component.html',
  styleUrls: ['./cost-basis.component.scss'],
})
export class CostBasisComponent {
  displayedColumns = [
    'tld',
    'operation',
    'costAmount',
    'costCurrency',
    'effectiveDate',
    'notes',
    'actions',
  ];

  showForm = signal(false);
  editingEntry = signal<CostBasisEntry | undefined>(undefined);

  costForm = new FormGroup({
    tld: new FormControl('', [Validators.required]),
    operation: new FormControl('', [Validators.required]),
    costAmount: new FormControl<number | null>(null, [
      Validators.required,
      Validators.min(0),
    ]),
    costCurrency: new FormControl('USD', [Validators.required]),
    effectiveDate: new FormControl(''),
    notes: new FormControl(''),
  });

  constructor(
    protected dashService: RegistryDashService,
    private snackBar: MatSnackBar
  ) {
    this.dashService.getCostBasis().subscribe();
  }

  openAddForm() {
    this.editingEntry.set(undefined);
    this.costForm.reset({ costCurrency: 'USD' });
    this.showForm.set(true);
  }

  openEditForm(entry: CostBasisEntry) {
    this.editingEntry.set(entry);
    this.costForm.patchValue({
      tld: entry.tld,
      operation: entry.operation,
      costAmount: entry.costAmount,
      costCurrency: entry.costCurrency,
      effectiveDate: entry.effectiveDate,
      notes: entry.notes || '',
    });
    this.showForm.set(true);
  }

  cancelForm() {
    this.showForm.set(false);
    this.editingEntry.set(undefined);
  }

  onSubmit() {
    if (!this.costForm.valid) return;

    const formValue = this.costForm.value;
    const entry: CostBasisEntry = {
      tld: formValue.tld!,
      operation: formValue.operation!,
      costAmount: formValue.costAmount!,
      costCurrency: formValue.costCurrency!,
      effectiveDate: formValue.effectiveDate || '',
      notes: formValue.notes || undefined,
    };

    const editing = this.editingEntry();
    if (editing?.id) {
      entry.id = editing.id;
      this.dashService.updateCostBasis(entry).subscribe({
        next: () => {
          this.snackBar.open('Cost basis updated', 'OK', { duration: 3000 });
          this.cancelForm();
        },
        error: (err) => this.snackBar.open(err.error || 'Update failed'),
      });
    } else {
      this.dashService.createCostBasis(entry).subscribe({
        next: () => {
          this.snackBar.open('Cost basis created', 'OK', { duration: 3000 });
          this.cancelForm();
        },
        error: (err) => this.snackBar.open(err.error || 'Create failed'),
      });
    }
  }
}
