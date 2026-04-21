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

import { Component, computed, inject, model, OnInit, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { MaterialModule } from '../../../material.module';
import { RANGE_CONFIG, RANGE_KEYS, RegistryDashService } from '../../registry-dash.service';
import { DATA_SOURCE_SCHEMAS } from '../data-source-schemas';
import {
  ChartType,
  DataSourceType,
  ExploreQuery,
  MetricSpec,
  SavedExploreView,
} from '../explore.models';
import { ExploreService } from '../explore.service';
import { SaveViewDialogComponent } from '../save-view-dialog.component';

const CHART_TYPES: { value: ChartType; icon: string; label: string }[] = [
  { value: 'bar', icon: 'bar_chart', label: 'Bar' },
  { value: 'line', icon: 'show_chart', label: 'Line' },
  { value: 'pie', icon: 'pie_chart', label: 'Pie' },
  { value: 'stacked-bar', icon: 'stacked_bar_chart', label: 'Stacked' },
  { value: 'area', icon: 'area_chart', label: 'Area' },
  { value: 'horizontal-bar', icon: 'align_horizontal_left', label: 'Horizontal Bar' },
];

const GRANULARITY_OPTIONS = [
  { value: 'day', label: 'Daily' },
  { value: 'week', label: 'Weekly' },
  { value: 'month', label: 'Monthly' },
];

@Component({
  selector: 'app-explore-builder',
  standalone: true,
  imports: [CommonModule, FormsModule, MaterialModule],
  templateUrl: './explore-builder.component.html',
  styleUrls: ['./explore-builder.component.scss'],
})
export class ExploreBuilderComponent implements OnInit {
  private dashService = inject(RegistryDashService);
  private dialog = inject(MatDialog);
  exploreService = inject(ExploreService);

  query = model.required<ExploreQuery>();
  chartType = model.required<ChartType>();

  /** Emitted when user wants to run the query explicitly. */
  run = output<void>();

  readonly schemas = DATA_SOURCE_SCHEMAS;
  readonly dataSourceKeys = Object.keys(DATA_SOURCE_SCHEMAS) as DataSourceType[];
  readonly chartTypes = CHART_TYPES;
  readonly granularityOptions = GRANULARITY_OPTIONS;
  readonly rangeKeys = RANGE_KEYS;

  selectedBucket = signal<string | null>(null);
  startTime: string | null = null;
  endTime: string | null = null;

  availableTlds = computed(() => this.dashService.availableTlds());
  availableRegistrars = computed(() => this.dashService.availableRegistrars());

  currentSchema = computed(() => this.schemas[this.query().dataSource]);

  showGranularity = computed(() => {
    const schema = this.currentSchema();
    const dims = this.query().dimensions;
    return schema.supportsGranularity && dims.includes('period');
  });

  recentViews = computed(() => this.exploreService.getRecentViews());
  savedViews = computed(() => this.exploreService.savedViews());

  ngOnInit(): void {
    this.exploreService.loadSavedViews();
    const globalRange = this.dashService.selectedTimeRange();
    if (globalRange && RANGE_CONFIG[globalRange]) {
      this.onBucketChange(globalRange);
    }
  }

  // --- Handlers ---

  onDataSourceChange(ds: DataSourceType): void {
    const schema = this.schemas[ds];
    const defaultMetric = schema.metrics[0];
    this.query.update(q => ({
      ...q,
      dataSource: ds,
      metrics: [{ field: defaultMetric.field, aggregation: 'sum' }],
      dimensions: schema.dimensions.length > 0 ? [schema.dimensions[0].field] : [],
      granularity: schema.supportsGranularity ? 'month' : undefined,
    }));
  }

  onMetricsChange(fields: string[]): void {
    this.query.update(q => ({
      ...q,
      metrics: fields.map(f => ({ field: f, aggregation: 'sum' as const })),
    }));
  }

  onDimensionsChange(fields: string[]): void {
    this.query.update(q => ({
      ...q,
      dimensions: fields,
    }));
  }

  onBucketChange(bucket: string): void {
    this.selectedBucket.set(bucket);
    const config = RANGE_CONFIG[bucket];
    if (!config) return;

    const now = new Date();
    const start = new Date(now.getTime() - config.lookbackHours * 3600_000);
    this.startTime = null;
    this.endTime = null;

    this.query.update(q => ({
      ...q,
      filters: {
        ...q.filters,
        dateRange: {
          start: this.formatDate(start),
          end: this.formatDate(now),
        },
      },
      granularity: config.granularity,
    }));
  }

  onGranularityChange(value: string): void {
    this.query.update(q => ({ ...q, granularity: value }));
  }

  onChartTypeChange(value: ChartType): void {
    this.chartType.set(value);
  }

  onTldFilterChange(tlds: string[]): void {
    this.query.update(q => ({
      ...q,
      filters: { ...q.filters, tlds: tlds.length > 0 ? tlds : undefined },
    }));
  }

  onRegistrarFilterChange(ids: string[]): void {
    this.query.update(q => ({
      ...q,
      filters: { ...q.filters, registrarIds: ids.length > 0 ? ids : undefined },
    }));
  }

  startDateValue = computed(() => {
    const s = this.query().filters.dateRange?.start;
    if (!s) return null;
    const datePart = s.substring(0, 10);
    return new Date(datePart + 'T00:00:00');
  });

  endDateValue = computed(() => {
    const e = this.query().filters.dateRange?.end;
    if (!e) return null;
    const datePart = e.substring(0, 10);
    return new Date(datePart + 'T00:00:00');
  });

  onDateStartChange(value: Date | null): void {
    this.selectedBucket.set(null);
    this.query.update(q => ({
      ...q,
      filters: {
        ...q.filters,
        dateRange: {
          start: value ? this.formatDateWithTime(value, this.startTime) : '',
          end: q.filters.dateRange?.end ?? '',
        },
      },
    }));
  }

  onDateEndChange(value: Date | null): void {
    this.selectedBucket.set(null);
    this.query.update(q => ({
      ...q,
      filters: {
        ...q.filters,
        dateRange: {
          start: q.filters.dateRange?.start ?? '',
          end: value ? this.formatDateWithTime(value, this.endTime) : '',
        },
      },
    }));
  }

  onStartTimeChange(time: string): void {
    this.startTime = time;
    this.selectedBucket.set(null);
    const currentStart = this.query().filters.dateRange?.start;
    if (currentStart) {
      const datePart = currentStart.substring(0, 10);
      this.query.update(q => ({
        ...q,
        filters: {
          ...q.filters,
          dateRange: {
            start: time ? `${datePart}T${time}` : datePart,
            end: q.filters.dateRange?.end ?? '',
          },
        },
      }));
    }
  }

  onEndTimeChange(time: string): void {
    this.endTime = time;
    this.selectedBucket.set(null);
    const currentEnd = this.query().filters.dateRange?.end;
    if (currentEnd) {
      const datePart = currentEnd.substring(0, 10);
      this.query.update(q => ({
        ...q,
        filters: {
          ...q.filters,
          dateRange: {
            start: q.filters.dateRange?.start ?? '',
            end: time ? `${datePart}T${time}` : datePart,
          },
        },
      }));
    }
  }

  private formatDate(d: Date): string {
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private formatDateWithTime(d: Date, time: string | null): string {
    const datePart = this.formatDate(d);
    return time ? `${datePart}T${time}` : datePart;
  }

  // --- Save / Load ---

  saveView(): void {
    this.dialog
      .open(SaveViewDialogComponent, {
        width: '360px',
        data: { title: 'Save View', label: 'View name' },
      })
      .afterClosed()
      .subscribe(name => {
        if (name) {
          this.exploreService.saveRecentView(name, this.query(), this.chartType());
        }
      });
  }

  loadView(view: SavedExploreView): void {
    this.query.set({ ...view.query });
    this.chartType.set(view.chartType);
  }

  deleteView(view: SavedExploreView, event: Event): void {
    event.stopPropagation();
    this.exploreService.deleteRecentView(view.name);
  }

  saveNamedView(): void {
    this.dialog
      .open(SaveViewDialogComponent, {
        width: '360px',
        data: { title: 'Save to Server', label: 'View name' },
      })
      .afterClosed()
      .subscribe(name => {
        if (name) {
          this.exploreService.saveNamedView(name, this.query(), this.chartType());
        }
      });
  }

  deleteNamedView(view: SavedExploreView, event: Event): void {
    event.stopPropagation();
    this.exploreService.deleteNamedView(view.name);
  }

  /** Helper to get the metric fields from the current query. */
  selectedMetricFields = computed(() => this.query().metrics.map(m => m.field));

  /** Whether tld filter is relevant for this data source. */
  showTldFilter = computed(() => this.currentSchema().filters.includes('tlds'));
  showRegistrarFilter = computed(() =>
    this.currentSchema().filters.includes('registrarIds'));
  showDateRange = computed(() => this.currentSchema().filters.includes('dateRange'));
}
