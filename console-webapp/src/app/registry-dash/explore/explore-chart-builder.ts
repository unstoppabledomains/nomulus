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

import { ChartType, ExploreResult } from './explore.models';

export function buildChartOptions(
  result: ExploreResult,
  chartType: ChartType,
  dimensions: string[],
  metricColumn: string,
): any {
  if (!result || result.rows.length === 0) return null;

  const primaryDim = dimensions[0];
  const secondaryDim = dimensions.length > 1 ? dimensions[1] : null;

  switch (chartType) {
    case 'pie':
      return buildPieOptions(result, primaryDim, metricColumn);
    case 'line':
      return buildLineOptions(result, primaryDim, secondaryDim, metricColumn);
    case 'area':
      return buildAreaOptions(result, primaryDim, secondaryDim, metricColumn);
    case 'stacked-bar':
      return buildStackedBarOptions(result, primaryDim, secondaryDim, metricColumn);
    case 'bar':
    default:
      return buildBarOptions(result, primaryDim, secondaryDim, metricColumn);
  }
}

function buildPieOptions(result: ExploreResult, dim: string, metric: string): any {
  const data = result.rows.map(r => ({
    name: String(r[dim] ?? 'Unknown'),
    value: Number(r[metric] ?? 0),
  }));
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { type: 'scroll', bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['35%', '65%'],
      data,
      emphasis: { itemStyle: { shadowBlur: 10 } },
    }],
  };
}

function buildBarOptions(
  result: ExploreResult, primaryDim: string,
  secondaryDim: string | null, metric: string,
): any {
  if (secondaryDim) {
    return buildGroupedBarOptions(result, primaryDim, secondaryDim, metric);
  }
  const categories = [...new Set(result.rows.map(r => String(r[primaryDim] ?? '')))];
  const values = categories.map(cat => {
    const row = result.rows.find(r => String(r[primaryDim]) === cat);
    return Number(row?.[metric] ?? 0);
  });
  return {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: categories, axisLabel: { rotate: categories.length > 10 ? 45 : 0 } },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: values }],
    grid: { bottom: categories.length > 10 ? 80 : 40 },
  };
}

function buildGroupedBarOptions(
  result: ExploreResult, primaryDim: string,
  secondaryDim: string, metric: string,
): any {
  const categories = [...new Set(result.rows.map(r => String(r[primaryDim] ?? '')))];
  const groups = [...new Set(result.rows.map(r => String(r[secondaryDim] ?? '')))];
  const series = groups.map(group => ({
    name: group,
    type: 'bar' as const,
    data: categories.map(cat => {
      const row = result.rows.find(
        r => String(r[primaryDim]) === cat && String(r[secondaryDim]) === group);
      return Number(row?.[metric] ?? 0);
    }),
  }));
  return {
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', bottom: 0 },
    xAxis: { type: 'category', data: categories, axisLabel: { rotate: categories.length > 10 ? 45 : 0 } },
    yAxis: { type: 'value' },
    series,
    grid: { bottom: 60 },
  };
}

function buildLineOptions(
  result: ExploreResult, primaryDim: string,
  secondaryDim: string | null, metric: string,
): any {
  const opts = buildBarOptions(result, primaryDim, secondaryDim, metric);
  for (const s of (opts.series || [])) {
    s.type = 'line';
    s.smooth = true;
  }
  return opts;
}

function buildAreaOptions(
  result: ExploreResult, primaryDim: string,
  secondaryDim: string | null, metric: string,
): any {
  const opts = buildLineOptions(result, primaryDim, secondaryDim, metric);
  for (const s of (opts.series || [])) {
    s.areaStyle = { opacity: 0.3 };
  }
  return opts;
}

function buildStackedBarOptions(
  result: ExploreResult, primaryDim: string,
  secondaryDim: string | null, metric: string,
): any {
  const opts = buildGroupedBarOptions(
    result, primaryDim, secondaryDim ?? primaryDim, metric);
  for (const s of (opts.series || [])) {
    s.stack = 'total';
  }
  return opts;
}
