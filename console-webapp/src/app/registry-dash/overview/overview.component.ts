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

import { Component, computed } from '@angular/core';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { RegistryDashService } from '../registry-dash.service';

const CHART_COLORS = [
  '#0D67FE', '#0546B7', '#65A1DA', '#192B55',
  '#00C9FF', '#0A5FEA', '#4A9B30', '#9191A1',
  '#3B82F6', '#7A7A85',
];

@Component({
  selector: 'app-registry-dash-overview',
  imports: [MaterialModule, CommonModule],
  templateUrl: './overview.component.html',
  styleUrls: ['./overview.component.scss'],
})
export class OverviewComponent {
  displayedColumns = ['registrarId', 'name', 'count'];

  donutSegments = computed(() => {
    const overview = this.dashService.overview();
    if (!overview) return [];
    const rows = overview.domainsByRegistrar.filter(r => r.count > 0);
    const total = rows.reduce((s, r) => s + r.count, 0);
    if (total === 0) return [];
    let startDeg = 0;
    return rows.map((r, i) => {
      const pct = r.count / total;
      const deg = pct * 360;
      const seg = { name: r.name || r.registrarId, count: r.count, pct, startDeg, deg, color: CHART_COLORS[i % CHART_COLORS.length] };
      startDeg += deg;
      return seg;
    });
  });

  donutGradient = computed(() => {
    const segs = this.donutSegments();
    if (segs.length === 0) return 'conic-gradient(var(--ud-border-subtle) 0deg 360deg)';
    const stops = segs.map(s => `${s.color} ${s.startDeg}deg ${s.startDeg + s.deg}deg`);
    return `conic-gradient(${stops.join(', ')})`;
  });

  barChartData = computed(() => {
    const overview = this.dashService.overview();
    if (!overview) return [];
    const rows = overview.domainsByRegistrar.filter(r => r.count > 0);
    const max = Math.max(...rows.map(r => r.count), 1);
    return rows.map((r, i) => ({
      name: r.name || r.registrarId,
      registrarId: r.registrarId,
      count: r.count,
      widthPct: (r.count / max) * 100,
      color: CHART_COLORS[i % CHART_COLORS.length],
    }));
  });

  constructor(protected dashService: RegistryDashService) {
    this.dashService.getOverview().subscribe();
  }
}
