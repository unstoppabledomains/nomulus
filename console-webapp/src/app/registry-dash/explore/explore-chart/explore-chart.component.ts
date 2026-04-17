import { Component, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MaterialModule } from '../../../material.module';
import { NgxEchartsDirective } from 'ngx-echarts';
import { UD_ECHARTS_PROVIDER } from '../../ud-echarts';
import { ChartType, ExploreResult } from '../explore.models';
import { buildChartOptions } from '../explore-chart-builder';

@Component({
  selector: 'app-explore-chart',
  standalone: true,
  imports: [CommonModule, MaterialModule, NgxEchartsDirective],
  providers: [UD_ECHARTS_PROVIDER],
  templateUrl: './explore-chart.component.html',
  styleUrls: ['./explore-chart.component.scss'],
})
export class ExploreChartComponent {
  result = input.required<ExploreResult | undefined>();
  chartType = input.required<ChartType>();
  dimensions = input.required<string[]>();
  metricColumn = input.required<string>();

  chartOptions = computed(() => {
    const r = this.result();
    if (!r || r.rows.length === 0) return null;
    return buildChartOptions(r, this.chartType(), this.dimensions(), this.metricColumn());
  });

  hasData = computed(() => {
    const r = this.result();
    return r != null && r.rows.length > 0;
  });
}
