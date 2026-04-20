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

import { Component, Inject, ViewChild, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { MaterialModule } from '../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../ud-echarts';
import { DrillDownDialogData } from './drilldown.models';

@Component({
  selector: 'app-drilldown-dialog',
  standalone: true,
  imports: [CommonModule, MaterialModule, MatSortModule, NgxEchartsDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './drilldown-dialog.component.html',
  styleUrls: ['./drilldown-dialog.component.scss'],
})
export class DrillDownDialogComponent implements AfterViewInit {
  displayedColumns: string[];
  dataSource: MatTableDataSource<Record<string, any>>;

  @ViewChild(MatSort) sort!: MatSort;

  constructor(
    public dialogRef: MatDialogRef<DrillDownDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DrillDownDialogData
  ) {
    this.displayedColumns = data.columns.map(c => c.key);
    this.dataSource = new MatTableDataSource(data.rows);
  }

  ngAfterViewInit() {
    this.dataSource.sort = this.sort;
  }

  formatValue(value: any, format?: 'number' | 'currency' | 'percent'): string {
    if (value == null) return '—';
    switch (format) {
      case 'currency':
        return `$${Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
      case 'percent':
        return `${Number(value).toFixed(1)}%`;
      case 'number':
        return Number(value).toLocaleString();
      default:
        return String(value);
    }
  }
}
