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
import { Router } from '@angular/router';
import { MaterialModule } from '../../material.module';
import { RegistryDashService } from '../registry-dash.service';

/** Routes where filtering is done server-side (re-fetches from DB). */
const SERVER_SIDE_ROUTES = new Set([
  'overview',
  'domain-activity',
  'financials',
]);

@Component({
  selector: 'app-registry-dash-filter-bar',
  standalone: true,
  imports: [CommonModule, MaterialModule],
  templateUrl: './filter-bar.component.html',
  styleUrls: ['./filter-bar.component.scss'],
})
export class FilterBarComponent {
  protected dashService = inject(RegistryDashService);
  private router = inject(Router);

  /** Cross-filtered registrar options: narrow by selected TLDs. */
  filteredRegistrarOptions = computed(() => {
    const all = this.dashService.availableRegistrars();
    const selectedTlds = this.dashService.selectedTlds();
    if (selectedTlds.length === 0) return all;
    return all.filter(r => r.allowedTlds.some(t => selectedTlds.includes(t)));
  });

  /** Cross-filtered TLD options: narrow by selected registrars. */
  filteredTldOptions = computed(() => {
    const all = this.dashService.availableTlds();
    const selectedRegIds = this.dashService.selectedRegistrarIds();
    if (selectedRegIds.length === 0) return all;
    const registrars = this.dashService.availableRegistrars()
      .filter(r => selectedRegIds.includes(r.registrarId));
    const tldSet = new Set(registrars.flatMap(r => r.allowedTlds));
    return all.filter(t => tldSet.has(t));
  });

  /** Whether the current page uses server-side or client-side filtering. */
  filterMode = computed<'server' | 'client'>(() => {
    const url = this.router.url;
    for (const route of SERVER_SIDE_ROUTES) {
      if (url.includes(route)) return 'server';
    }
    return 'client';
  });

  filterModeTooltip = computed(() =>
    this.filterMode() === 'server'
      ? 'Filters applied server-side — data re-fetched from database'
      : 'Filters applied locally — subset of already-loaded data'
  );

  filterModeIcon = computed(() =>
    this.filterMode() === 'server' ? 'cloud' : 'devices'
  );

  onRegistrarsChange(ids: string[]) {
    this.dashService.selectedRegistrarIds.set(ids);
  }

  onTldsChange(tlds: string[]) {
    this.dashService.selectedTlds.set(tlds);
  }

  clearFilters() {
    this.dashService.clearFilters();
  }
}
