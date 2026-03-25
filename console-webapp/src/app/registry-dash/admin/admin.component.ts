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

import { Component, OnInit, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { MaterialModule } from '../../material.module';
import { RegistryDashService, RoRegistry, CostBasisEntry } from '../registry-dash.service';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, MaterialModule, ReactiveFormsModule],
  templateUrl: './admin.component.html',
  styleUrls: ['./admin.component.scss'],
})
export class AdminComponent implements OnInit {
  registrarColumns = ['registrarId', 'registrarName', 'allowedTlds'];

  registries = computed(() => this.dashService.adminData()?.registries || []);
  systemInfo = computed(() => this.dashService.adminData()?.systemInfo);

  selectedRegistry = signal<RoRegistry | undefined>(undefined);

  registryForm = new FormGroup({
    name: new FormControl('', [Validators.required]),
  });

  tldForm = new FormGroup({
    tld: new FormControl('', [Validators.required]),
  });

  userForm = new FormGroup({
    userEmail: new FormControl('', [Validators.required, Validators.email]),
  });

  // Cost Basis management
  costBasisEntries = computed(() => this.dashService.costBasis());
  costBasisColumns = ['tld', 'operation', 'registrarId', 'costAmount', 'costCurrency', 'notes', 'actions'];

  showCostBasisForm = signal(false);
  editingCostBasis = signal<CostBasisEntry | undefined>(undefined);

  costBasisForm = new FormGroup({
    tld: new FormControl('', [Validators.required]),
    operation: new FormControl('', [Validators.required]),
    registrarId: new FormControl(''),
    costAmount: new FormControl<number | null>(null, [Validators.required, Validators.min(0)]),
    costCurrency: new FormControl('USD', [Validators.required]),
    notes: new FormControl(''),
  });

  operations = ['CREATE', 'RENEW', 'RESTORE', 'TRANSFER'];

  constructor(public dashService: RegistryDashService) {}

  ngOnInit() {
    this.dashService.getAdminData().subscribe();
    this.dashService.getCostBasis().subscribe();
  }

  onCreateRegistry() {
    if (this.registryForm.invalid) return;
    const name = this.registryForm.value.name!;
    this.dashService
      .adminAction({ action: 'createRegistry', registryName: name })
      .subscribe(() => {
        this.registryForm.reset();
      });
  }

  onDeleteRegistry(id: number) {
    this.dashService
      .adminAction({ action: 'deleteRegistry', registryId: id })
      .subscribe(() => {
        if (this.selectedRegistry()?.id === id) {
          this.selectedRegistry.set(undefined);
        }
      });
  }

  onSelectRegistry(registry: RoRegistry) {
    this.selectedRegistry.set(registry);
  }

  onAddTld() {
    if (this.tldForm.invalid || !this.selectedRegistry()) return;
    const tld = this.tldForm.value.tld!;
    this.dashService
      .adminAction({
        action: 'addTld',
        registryId: this.selectedRegistry()!.id,
        tld,
      })
      .subscribe(() => {
        this.tldForm.reset();
        this.refreshSelectedRegistry();
      });
  }

  onRemoveTld(id: number) {
    this.dashService
      .adminAction({ action: 'removeTld', id })
      .subscribe(() => this.refreshSelectedRegistry());
  }

  onAddUser() {
    if (this.userForm.invalid || !this.selectedRegistry()) return;
    const userEmail = this.userForm.value.userEmail!;
    this.dashService
      .adminAction({
        action: 'addUser',
        registryId: this.selectedRegistry()!.id,
        userEmail,
      })
      .subscribe(() => {
        this.userForm.reset();
        this.refreshSelectedRegistry();
      });
  }

  onRemoveUser(id: number) {
    this.dashService
      .adminAction({ action: 'removeUser', id })
      .subscribe(() => this.refreshSelectedRegistry());
  }

  /** After admin data refreshes, update the selected registry reference. */
  private refreshSelectedRegistry() {
    const currentId = this.selectedRegistry()?.id;
    if (currentId) {
      // adminAction already triggers getAdminData refresh; update selection on next tick
      setTimeout(() => {
        const updated = this.registries().find((r) => r.id === currentId);
        this.selectedRegistry.set(updated);
      }, 500);
    }
  }

  /** TLDs available for assignment (not already assigned to this registry). */
  availableTlds = computed(() => {
    const all = this.systemInfo()?.tlds || [];
    const assigned = (this.selectedRegistry()?.tlds || []).map((t) => t.tld);
    return all.filter((t) => !assigned.includes(t));
  });

  onAddCostBasis() {
    this.editingCostBasis.set(undefined);
    this.costBasisForm.reset({ costCurrency: 'USD' });
    this.showCostBasisForm.set(true);
  }

  onEditCostBasis(entry: CostBasisEntry) {
    this.editingCostBasis.set(entry);
    this.costBasisForm.patchValue({
      tld: entry.tld,
      operation: entry.operation,
      registrarId: entry.registrarId || '',
      costAmount: entry.costAmount,
      costCurrency: entry.costCurrency,
      notes: entry.notes || '',
    });
    this.showCostBasisForm.set(true);
  }

  onSaveCostBasis() {
    if (this.costBasisForm.invalid) return;
    const val = this.costBasisForm.value;
    const entry: CostBasisEntry = {
      tld: val.tld!,
      operation: val.operation!,
      registrarId: val.registrarId || undefined,
      costAmount: val.costAmount!,
      costCurrency: val.costCurrency!,
      notes: val.notes || undefined,
      effectiveDate: new Date().toISOString(),
    };
    const editing = this.editingCostBasis();
    if (editing?.id) {
      entry.id = editing.id;
      this.dashService.updateCostBasis(entry).subscribe(() => {
        this.showCostBasisForm.set(false);
      });
    } else {
      this.dashService.createCostBasis(entry).subscribe(() => {
        this.showCostBasisForm.set(false);
      });
    }
  }

  onCancelCostBasis() {
    this.showCostBasisForm.set(false);
    this.editingCostBasis.set(undefined);
  }
}
