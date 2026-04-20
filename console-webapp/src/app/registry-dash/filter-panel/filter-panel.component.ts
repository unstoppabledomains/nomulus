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

import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MaterialModule } from '../../material.module';
import { RegistryDashService } from '../registry-dash.service';

interface FilterChip {
  label: string;
  type: 'tld' | 'registrar';
  value: string;
}

@Component({
  selector: 'app-filter-panel',
  standalone: true,
  imports: [CommonModule, MaterialModule],
  templateUrl: './filter-panel.component.html',
  styleUrls: ['./filter-panel.component.scss'],
})
export class FilterPanelComponent {
  protected dashService = inject(RegistryDashService);

  expanded = computed(() => this.dashService.filterPanelExpanded());

  activeChips = computed<FilterChip[]>(() => {
    const chips: FilterChip[] = [];
    for (const tld of this.dashService.selectedTlds()) {
      chips.push({ label: '.' + tld, type: 'tld', value: tld });
    }
    for (const id of this.dashService.selectedRegistrarIds()) {
      const reg = this.dashService.availableRegistrars().find(r => r.registrarId === id);
      chips.push({ label: reg?.registrarName || id, type: 'registrar', value: id });
    }
    return chips;
  });

  filteredRegistrarOptions = computed(() => {
    const all = this.dashService.availableRegistrars();
    const selectedTlds = this.dashService.selectedTlds();
    if (selectedTlds.length === 0) return all;
    return all.filter(r => r.allowedTlds.some(t => selectedTlds.includes(t)));
  });

  filteredTldOptions = computed(() => {
    const all = this.dashService.availableTlds();
    const selectedRegIds = this.dashService.selectedRegistrarIds();
    if (selectedRegIds.length === 0) return all;
    const registrars = this.dashService.availableRegistrars()
      .filter(r => selectedRegIds.includes(r.registrarId));
    const tldSet = new Set(registrars.flatMap(r => r.allowedTlds));
    return all.filter(t => tldSet.has(t));
  });

  removeChip(chip: FilterChip): void {
    if (chip.type === 'tld') {
      this.dashService.selectedTlds.update(t => t.filter(x => x !== chip.value));
    } else {
      this.dashService.selectedRegistrarIds.update(r => r.filter(x => x !== chip.value));
    }
  }

  onTldsChange(tlds: string[]): void {
    this.dashService.selectedTlds.set(tlds);
  }

  onRegistrarsChange(ids: string[]): void {
    this.dashService.selectedRegistrarIds.set(ids);
  }

  clearFilters(): void {
    this.dashService.clearFilters();
  }

  toggleExpanded(): void {
    this.dashService.filterPanelExpanded.update(v => !v);
  }
}
