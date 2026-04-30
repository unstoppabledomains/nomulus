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

import { Component, computed, DestroyRef, effect, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { MatDialog } from '@angular/material/dialog';
import { catchError, debounceTime, EMPTY, filter, switchMap } from 'rxjs';
import { MaterialModule } from '../../material.module';
import { RegistryDashService } from '../registry-dash.service';
import { ExploreService } from './explore.service';
import { ExploreBuilderComponent } from './explore-builder/explore-builder.component';
import { ExploreChartComponent } from './explore-chart/explore-chart.component';
import { ChartType, DEFAULT_QUERY, ExploreQuery } from './explore.models';
import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';
import { EXPLORE_PROMPTS } from '../ai/ai-prompts';
import { AiAnalysisService } from '../ai/ai-analysis.service';
import {
  AiAnalysisModalComponent,
  AiAnalysisModalData,
} from '../ai/ai-analysis-modal.component';
import { EXPLORE_AI_ROW_CAP, AiModelChoice } from '../ai/ai-analysis.models';

@Component({
  selector: 'app-explore',
  standalone: true,
  imports: [CommonModule, MaterialModule, ExploreBuilderComponent, ExploreChartComponent, AiSparkleButtonComponent],
  templateUrl: './explore.component.html',
  styleUrls: ['./explore.component.scss'],
})
export class ExploreComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  private dashService = inject(RegistryDashService);
  private dialog = inject(MatDialog);
  exploreService = inject(ExploreService);
  aiService = inject(AiAnalysisService);
  readonly aiPrompts = EXPLORE_PROMPTS;

  query = signal<ExploreQuery>({ ...DEFAULT_QUERY, filters: { ...DEFAULT_QUERY.filters } });
  chartType = signal<ChartType>('bar');
  autoUpdate = signal(false);

  result = computed(() => this.exploreService.result());
  loading = computed(() => this.exploreService.loading());
  error = computed(() => this.exploreService.error());

  metricColumn = computed(() => {
    const metrics = this.query().metrics;
    return metrics.length > 0 ? `${metrics[0].field}_${metrics[0].aggregation}` : 'count_sum';
  });

  tableColumns = computed(() => {
    const r = this.result();
    return r ? r.columns : [];
  });

  constructor() {
    toObservable(this.query).pipe(
      debounceTime(500),
      filter(() => this.autoUpdate()),
      switchMap(q =>
        this.exploreService.explore(q).pipe(catchError(() => EMPTY))
      ),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
  }

  ngOnInit(): void {
    const pending = this.exploreService.pendingQuery();
    if (pending) {
      this.query.set(pending.query);
      this.chartType.set(pending.chartType);
      this.exploreService.pendingQuery.set(null);
      this.runQuery();
      return;
    }

    if (this.dashService.hasActiveFilters()) {
      const tlds = this.dashService.selectedTlds();
      const regIds = this.dashService.selectedRegistrarIds();
      this.query.update(q => ({
        ...q,
        filters: {
          ...q.filters,
          tlds: tlds.length > 0 ? [...tlds] : undefined,
          registrarIds: regIds.length > 0 ? [...regIds] : undefined,
        },
      }));
    }
  }

  runQuery(): void {
    this.exploreService.explore(this.query()).subscribe();
  }

  truncatedChartData() {
    const r = this.result();
    if (!r) return null;
    const cap = EXPLORE_AI_ROW_CAP;
    const slicedRows = r.rows.slice(0, cap);
    return {
      columns: r.columns,
      rows: slicedRows,
      totalRows: r.totalRows,
      returnedRows: slicedRows.length,
      truncated: r.rows.length > cap,
    };
  }

  descriptorSummary(): string {
    const q = this.query();
    const metricStr = q.metrics
      .map(m => `${m.field}_${m.aggregation}`)
      .join(', ') || 'count_sum';
    const dimsStr = q.dimensions.length > 0
      ? `grouped by ${q.dimensions.join(', ')}`
      : 'no grouping';
    const range = this.dashService.selectedRangeConfig();
    const rangeLabel = range ? `last ${range.lookbackHours}h, ${range.granularity}` : '';
    return [q.dataSource, dimsStr, metricStr, rangeLabel]
      .filter(Boolean)
      .join(', ');
  }

  private buildMetadata(): AiAnalysisModalData['metadata'] {
    const range = this.dashService.selectedRangeConfig();
    return {
      dateRange: { start: '', end: '' },
      granularity: range?.granularity,
      filteredTlds: this.dashService.selectedTlds(),
      filteredRegistrars: this.dashService.selectedRegistrarIds(),
      exploreDescriptor: this.query(),
    };
  }

  private savedAiModel(): AiModelChoice | undefined {
    return (this.dashService.settingsCache()?.['aiModel']
      || localStorage.getItem('ai-model-preference')) as AiModelChoice | undefined;
  }

  addToNewChat(): void {
    const truncated = this.truncatedChartData();
    if (!truncated) return;
    this.aiService.resetConversation();

    const data: AiAnalysisModalData = {
      title: 'Explore Analysis — Data Exploration',
      page: 'explore',
      promptType: 'summarize_trends',
      userMessage: EXPLORE_PROMPTS[0].userMessage,
      metadata: this.buildMetadata(),
      chartData: truncated,
      isAdmin: false,
      savedModel: this.savedAiModel(),
    };

    this.dialog.open(AiAnalysisModalComponent, {
      width: '800px',
      maxHeight: '90vh',
      data,
    });
  }

  addToCurrentChat(): void {
    if (!this.aiService.hasActiveConversation()) return;
    const truncated = this.truncatedChartData();
    if (!truncated) return;

    const userTurn =
      `I just ran an Explore query: ${this.descriptorSummary()}.\n\n` +
      `Descriptor: ${JSON.stringify(this.query())}\n\n` +
      `Data (${truncated.returnedRows} of ${truncated.totalRows} rows` +
      (truncated.truncated ? ', truncated' : '') +
      `): ${JSON.stringify(truncated.rows)}`;

    this.aiService.appendUserTurnAndAnalyze(userTurn, {
      chartData: truncated,
      metadata: this.buildMetadata(),
      page: 'explore',
      promptType: 'summarize_trends',
    });

    const data: AiAnalysisModalData = {
      title: 'Explore Analysis — Data Exploration',
      page: 'explore',
      promptType: 'summarize_trends',
      userMessage: EXPLORE_PROMPTS[0].userMessage,
      metadata: this.buildMetadata(),
      chartData: truncated,
      isAdmin: false,
      savedModel: this.savedAiModel(),
    };
    this.dialog.open(AiAnalysisModalComponent, {
      width: '800px',
      maxHeight: '90vh',
      data,
    });
  }

  exportCsv(): void {
    const r = this.result();
    if (!r || r.rows.length === 0) return;

    const escape = (val: unknown): string => {
      const s = val == null ? '' : String(val);
      if (s.includes(',') || s.includes('"') || s.includes('\n')) {
        return '"' + s.replace(/"/g, '""') + '"';
      }
      return s;
    };

    const header = r.columns.map(escape).join(',');
    const rows = r.rows.map(row => r.columns.map(col => escape(row[col])).join(','));
    const csv = '﻿' + header + '\n' + rows.join('\n');

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    a.href = url;
    a.download = `explore-${this.query().dataSource.toLowerCase()}-${ts}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}
