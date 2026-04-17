import { Component, computed, inject, model, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MaterialModule } from '../../../material.module';
import { RegistryDashService } from '../../registry-dash.service';
import { DATA_SOURCE_SCHEMAS } from '../data-source-schemas';
import {
  ChartType,
  DataSourceType,
  ExploreQuery,
  MetricSpec,
  SavedExploreView,
} from '../explore.models';
import { ExploreService } from '../explore.service';

const CHART_TYPES: { value: ChartType; icon: string; label: string }[] = [
  { value: 'bar', icon: 'bar_chart', label: 'Bar' },
  { value: 'line', icon: 'show_chart', label: 'Line' },
  { value: 'pie', icon: 'pie_chart', label: 'Pie' },
  { value: 'stacked-bar', icon: 'stacked_bar_chart', label: 'Stacked' },
  { value: 'area', icon: 'area_chart', label: 'Area' },
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
export class ExploreBuilderComponent {
  private dashService = inject(RegistryDashService);
  private exploreService = inject(ExploreService);

  query = model.required<ExploreQuery>();
  chartType = model.required<ChartType>();

  /** Emitted when user wants to run the query explicitly. */
  run = output<void>();

  readonly schemas = DATA_SOURCE_SCHEMAS;
  readonly dataSourceKeys = Object.keys(DATA_SOURCE_SCHEMAS) as DataSourceType[];
  readonly chartTypes = CHART_TYPES;
  readonly granularityOptions = GRANULARITY_OPTIONS;

  availableTlds = computed(() => this.dashService.availableTlds());
  availableRegistrars = computed(() => this.dashService.availableRegistrars());

  currentSchema = computed(() => this.schemas[this.query().dataSource]);

  showGranularity = computed(() => {
    const schema = this.currentSchema();
    const dims = this.query().dimensions;
    return schema.supportsGranularity && dims.includes('period');
  });

  recentViews = computed(() => this.exploreService.getRecentViews());

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

  onDateStartChange(value: string): void {
    this.query.update(q => ({
      ...q,
      filters: {
        ...q.filters,
        dateRange: {
          start: value,
          end: q.filters.dateRange?.end ?? '',
        },
      },
    }));
  }

  onDateEndChange(value: string): void {
    this.query.update(q => ({
      ...q,
      filters: {
        ...q.filters,
        dateRange: {
          start: q.filters.dateRange?.start ?? '',
          end: value,
        },
      },
    }));
  }

  // --- Save / Load ---

  saveView(): void {
    const name = prompt('Enter a name for this view:');
    if (name) {
      this.exploreService.saveRecentView(name, this.query(), this.chartType());
    }
  }

  loadView(view: SavedExploreView): void {
    this.query.set({ ...view.query });
    this.chartType.set(view.chartType);
  }

  deleteView(view: SavedExploreView, event: Event): void {
    event.stopPropagation();
    this.exploreService.deleteRecentView(view.name);
  }

  /** Helper to get the metric fields from the current query. */
  selectedMetricFields = computed(() => this.query().metrics.map(m => m.field));

  /** Whether tld filter is relevant for this data source. */
  showTldFilter = computed(() => this.currentSchema().filters.includes('tlds'));
  showRegistrarFilter = computed(() =>
    this.currentSchema().filters.includes('registrarIds'));
  showDateRange = computed(() => this.currentSchema().filters.includes('dateRange'));
}
