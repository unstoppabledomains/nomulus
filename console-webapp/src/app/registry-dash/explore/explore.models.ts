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

export type DataSourceType =
  | 'DOMAIN_ACTIVITY'
  | 'REVENUE'
  | 'DOMAIN_COUNTS'
  | 'RENEWAL_RATES'
  | 'EXPIRATION_CURVE'
  | 'PRICING_RULES'
  | 'TRANSACTIONS';

export type ChartType = 'bar' | 'line' | 'pie' | 'stacked-bar' | 'area' | 'horizontal-bar';

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
