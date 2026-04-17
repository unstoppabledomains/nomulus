export type DataSourceType =
  | 'DOMAIN_ACTIVITY'
  | 'REVENUE'
  | 'DOMAIN_COUNTS'
  | 'RENEWAL_RATES'
  | 'EXPIRATION_CURVE'
  | 'PRICING_RULES';

export type ChartType = 'bar' | 'line' | 'pie' | 'stacked-bar' | 'area';

export interface MetricSpec {
  field: string;
  aggregation: 'sum' | 'count' | 'avg';
}

export interface DateRange {
  start: string;
  end: string;
}

export interface ExploreFilters {
  tlds?: string[];
  registrarIds?: string[];
  activityTypes?: string[];
  operations?: string[];
  dateRange?: DateRange;
}

export interface ExploreQuery {
  dataSource: DataSourceType;
  metrics: MetricSpec[];
  dimensions: string[];
  filters: ExploreFilters;
  granularity?: string;
  limit?: number;
}

export interface ExploreResult {
  columns: string[];
  rows: Record<string, any>[];
  truncated: boolean;
  totalRows: number;
}

export interface SavedExploreView {
  name: string;
  query: ExploreQuery;
  chartType: ChartType;
  savedAt: string;
}

export interface DataSourceSchema {
  label: string;
  description: string;
  metrics: { field: string; label: string }[];
  dimensions: { field: string; label: string }[];
  filters: string[];
  supportsGranularity: boolean;
}

export const DEFAULT_QUERY: ExploreQuery = {
  dataSource: 'DOMAIN_ACTIVITY',
  metrics: [{ field: 'count', aggregation: 'sum' }],
  dimensions: ['tld'],
  filters: {},
};
