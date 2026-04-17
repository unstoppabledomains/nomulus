import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { catchError, debounceTime, EMPTY, switchMap } from 'rxjs';
import { MaterialModule } from '../../material.module';
import { RegistryDashService } from '../registry-dash.service';
import { ExploreService } from './explore.service';
import { ExploreBuilderComponent } from './explore-builder/explore-builder.component';
import { ExploreChartComponent } from './explore-chart/explore-chart.component';
import { ChartType, DEFAULT_QUERY, ExploreQuery } from './explore.models';

@Component({
  selector: 'app-explore',
  standalone: true,
  imports: [CommonModule, MaterialModule, ExploreBuilderComponent, ExploreChartComponent],
  templateUrl: './explore.component.html',
  styleUrls: ['./explore.component.scss'],
})
export class ExploreComponent implements OnInit {
  private destroyRef = inject(DestroyRef);
  private dashService = inject(RegistryDashService);
  exploreService = inject(ExploreService);

  query = signal<ExploreQuery>({ ...DEFAULT_QUERY, filters: { ...DEFAULT_QUERY.filters } });
  chartType = signal<ChartType>('bar');

  result = computed(() => this.exploreService.result());
  loading = computed(() => this.exploreService.loading());
  error = computed(() => this.exploreService.error());

  /** First metric field — used as the value column for chart rendering. */
  metricColumn = computed(() => {
    const metrics = this.query().metrics;
    return metrics.length > 0 ? metrics[0].field : 'count';
  });

  /** Dynamic table columns — dimensions + metric fields. */
  tableColumns = computed(() => {
    const r = this.result();
    return r ? r.columns : [];
  });

  constructor() {
    // Reactive fetch: debounce query changes, then call explore API
    toObservable(this.query)
      .pipe(
        debounceTime(500),
        switchMap(q =>
          this.exploreService.explore(q).pipe(catchError(() => EMPTY))
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe();
  }

  ngOnInit(): void {
    // Seed query filters from global filter bar if active
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
}
