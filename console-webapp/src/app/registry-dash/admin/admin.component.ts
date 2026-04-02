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
  costBasisColumns = ['tld', 'operation', 'registrarPays', 'rspFee', 'netToRegistry', 'costCurrency', 'notes', 'actions'];

  showCostBasisForm = signal(false);
  editingCostBasis = signal<CostBasisEntry | undefined>(undefined);

  costBasisForm = new FormGroup({
    tld: new FormControl('', [Validators.required]),
    operation: new FormControl('', [Validators.required]),
    rspRetainedFeeAmount: new FormControl<number | null>(null, [Validators.required, Validators.min(0)]),
    costCurrency: new FormControl('USD', [Validators.required]),
    notes: new FormControl(''),
  });

  operations = ['CREATE', 'RENEW', 'RESTORE', 'TRANSFER'];

  // Column visibility settings
  static readonly VISIBILITY_KEYS = [
    { key: 'pricing.priceAmount', label: 'Pricing: Price' },
    { key: 'pricing.defaultPrice', label: 'Pricing: Default Price' },
    { key: 'pricing.difference', label: 'Pricing: Difference' },
    { key: 'financials.feesRspPays', label: 'Financials: RSP Fee column' },
    { key: 'financials.feesNetToRegistry', label: 'Financials: Net to Registry column' },
    { key: 'financials.entityBreakdownChart', label: 'Financials: Entity Breakdown Chart' },
  ];
  visibilityKeys = AdminComponent.VISIBILITY_KEYS;

  // My View — FTE session-only toggles (not persisted, not per-registry)
  myViewToggles = signal<Record<string, boolean>>({});

  // Per-registry policy toggles (persisted to DB)
  visibilityToggles = signal<Record<string, boolean>>({});

  constructor(public dashService: RegistryDashService) {}

  ngOnInit() {
    this.dashService.getAdminData().subscribe();
    this.dashService.getCostBasis().subscribe();
    this.loadMyViewToggles();
  }

  // --- My View (FTE session-only) ---

  /** Load My View toggles from the current session state. */
  private loadMyViewToggles() {
    const cv = this.dashService.columnVisibility();
    const toggles: Record<string, boolean> = {};
    for (const item of AdminComponent.VISIBILITY_KEYS) {
      toggles[item.key] = cv[item.key] !== false;
    }
    this.myViewToggles.set(toggles);
  }

  onMyViewToggle(key: string, checked: boolean) {
    this.myViewToggles.update(v => ({ ...v, [key]: checked }));
    // Apply to session immediately
    const toggles = this.myViewToggles();
    const cv: Record<string, boolean> = {};
    for (const [k, value] of Object.entries(toggles)) {
      if (!value) cv[k] = false;
    }
    this.dashService.columnVisibility.set(cv);
  }

  onResetMyView() {
    this.dashService.columnVisibility.set({ ...RegistryDashService.DEFAULT_VISIBILITY });
    this.loadMyViewToggles();
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
    this.loadVisibilityToggles();
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
      rspRetainedFeeAmount: entry.rspRetainedFeeAmount,
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
      rspRetainedFeeAmount: val.rspRetainedFeeAmount!,
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

  // --- Per-Registry Policy (persisted to DB) ---

  /** Load per-registry visibility toggles from the registry's persisted settings (merged with defaults). */
  loadVisibilityToggles() {
    const registry = this.selectedRegistry();
    if (!registry) return;
    try {
      const settings = registry.settings ? JSON.parse(registry.settings) : {};
      const cv = { ...RegistryDashService.DEFAULT_VISIBILITY, ...(settings.columnVisibility ?? {}) };
      const toggles: Record<string, boolean> = {};
      for (const item of AdminComponent.VISIBILITY_KEYS) {
        toggles[item.key] = cv[item.key] !== false;
      }
      this.visibilityToggles.set(toggles);
    } catch {
      this.visibilityToggles.set({});
    }
  }

  onVisibilityToggle(key: string, checked: boolean) {
    this.visibilityToggles.update(v => ({ ...v, [key]: checked }));
  }

  onSaveVisibility() {
    const registry = this.selectedRegistry();
    if (!registry?.id) return;
    const toggles = this.visibilityToggles();
    const columnVisibility: Record<string, boolean> = {};
    for (const [key, value] of Object.entries(toggles)) {
      if (!value) columnVisibility[key] = false;
    }
    const settings = JSON.stringify({ columnVisibility });
    this.dashService.updateSettings(registry.id, settings).subscribe(() => {
      this.refreshSelectedRegistry();
    });
  }
}
