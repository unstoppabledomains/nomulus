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
import { CommonModule } from '@angular/common';
import { MaterialModule } from '../../../material.module';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { RegistryDashService, EffectiveFeeEntry } from '../../registry-dash.service';

@Component({
  selector: 'app-effective-fees',
  standalone: true,
  imports: [CommonModule, MaterialModule, MatSortModule],
  templateUrl: './effective-fees.component.html',
  styleUrls: ['./effective-fees.component.scss'],
})
export class EffectiveFeesComponent implements AfterViewInit {
  @ViewChild(MatSort) sort!: MatSort;

  displayedColumns = [
    'registrarId', 'registrarName', 'tld', 'operation', 'price', 'currency', 'source',
  ];

  fetchedAt = signal<string>('');
  filterSource = signal<string>('all');

  filteredFees = computed(() => {
    let fees = this.dashService.filteredEffectiveFees();
    const source = this.filterSource();
    if (source !== 'all') fees = fees.filter(f => f.source === source);
    return fees;
  });

  dataSource = new MatTableDataSource<EffectiveFeeEntry>([]);

  constructor(public dashService: RegistryDashService) {
    this.dashService.getEffectiveFees().subscribe(() => {
      this.fetchedAt.set(
        new Date().toLocaleDateString('en-US', {
          month: 'short', day: 'numeric', year: 'numeric',
          hour: 'numeric', minute: '2-digit', timeZone: 'UTC', timeZoneName: 'short',
        })
      );
    });

    effect(() => {
      this.dataSource.data = this.filteredFees();
    });
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.sort;
  }

  onSourceFilterChange(value: string) {
    this.filterSource.set(value);
  }
}
