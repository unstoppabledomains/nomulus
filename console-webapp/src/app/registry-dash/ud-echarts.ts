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

import * as echarts from 'echarts/core';
import { LineChart, BarChart, PieChart } from 'echarts/charts';
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  ToolboxComponent,
  TitleComponent,
  MarkLineComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { provideEchartsCore } from 'ngx-echarts';

// Register only the chart types and components we need (tree-shaking)
echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  ToolboxComponent,
  TitleComponent,
  MarkLineComponent,
  CanvasRenderer,
]);

// UD brand theme — matches colors extracted from unstoppabledomains.com
echarts.registerTheme('ud-brand', {
  color: [
    '#0D67FE', // UD Blue (primary)
    '#0546B7', // Dark Blue
    '#65A1DA', // Light Blue
    '#192B55', // Navy
    '#00C9FF', // Cyan
    '#0A5FEA', // Medium Blue
    '#4A9B30', // UD Green (only non-blue accent)
    '#9191A1', // Muted Gray
  ],
  backgroundColor: 'transparent',
  textStyle: {
    fontFamily: 'Inter, Google Sans, sans-serif',
    color: '#62626A',
  },
  title: {
    textStyle: { color: '#323034', fontWeight: 600, fontSize: 15 },
    subtextStyle: { color: '#9191A1' },
  },
  line: {
    itemStyle: { borderWidth: 2 },
    lineStyle: { width: 2 },
    symbolSize: 6,
    smooth: false,
  },
  bar: {
    itemStyle: { borderRadius: [4, 4, 0, 0] },
  },
  pie: {
    itemStyle: { borderColor: '#fff', borderWidth: 2 },
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#E8E8EA' } },
    axisTick: { lineStyle: { color: '#E8E8EA' } },
    axisLabel: { color: '#62626A', fontSize: 12 },
    splitLine: { lineStyle: { color: '#F5F5F5' } },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#9191A1', fontSize: 12 },
    splitLine: { lineStyle: { color: '#F5F5F5', type: 'dashed' } },
  },
  tooltip: {
    backgroundColor: '#fff',
    borderColor: '#E8E8EA',
    borderWidth: 1,
    textStyle: { color: '#323034', fontSize: 13 },
    extraCssText: 'box-shadow: 0 4px 12px rgba(0,0,0,0.08); border-radius: 8px;',
  },
  legend: {
    textStyle: { color: '#62626A', fontSize: 12 },
    itemGap: 16,
  },
  dataZoom: {
    borderColor: '#E8E8EA',
    textStyle: { color: '#9191A1' },
    handleStyle: { color: '#0D67FE' },
    fillerColor: 'rgba(13, 103, 254, 0.08)',
  },
});

/** Adds click affordance to an ECharts series: pointer cursor + emphasis focus. */
export function withDrillDown<T extends Record<string, any>>(series: T): T {
  return {
    ...series,
    emphasis: { ...(series['emphasis'] ?? {}), focus: 'series' },
    cursor: 'pointer',
  };
}

/** Provide this in component `providers` to wire up ngx-echarts with tree-shaken core. */
export const UD_ECHARTS_PROVIDER = provideEchartsCore({ echarts });

export { echarts };
