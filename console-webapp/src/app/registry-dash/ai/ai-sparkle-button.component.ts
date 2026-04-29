// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialog } from '@angular/material/dialog';
import { MaterialModule } from '../../material.module';
import {
  AiAnalysisModalComponent,
  AiAnalysisModalData,
} from './ai-analysis-modal.component';
import { AiPromptOption, AiAnalyzeRequest, AiModelChoice } from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-ai-sparkle-button',
  standalone: true,
  imports: [CommonModule, MaterialModule],
  templateUrl: './ai-sparkle-button.component.html',
  styleUrls: ['./ai-sparkle-button.component.scss'],
})
export class AiSparkleButtonComponent {
  @Input({ required: true }) page!: AiAnalyzeRequest['page'];
  @Input({ required: true }) prompts!: AiPromptOption[];
  @Input({ required: true }) chartData!: any;
  @Input() isAdmin = false;

  constructor(
    private dialog: MatDialog,
    private dashService: RegistryDashService,
  ) {}

  onPromptSelect(prompt: AiPromptOption) {
    const range = this.dashService.selectedRangeConfig();
    const tlds = this.dashService.selectedTlds();
    const regIds = this.dashService.selectedRegistrarIds();
    const savedModel = (this.dashService.settingsCache()?.['aiModel']
      || localStorage.getItem('ai-model-preference')) as AiModelChoice | undefined;

    const data: AiAnalysisModalData = {
      title: `${prompt.label} — ${this.pageLabel()}`,
      page: this.page,
      promptType: prompt.promptType,
      userMessage: prompt.userMessage,
      metadata: {
        dateRange: { start: '', end: '' },
        granularity: range?.granularity,
        filteredTlds: tlds,
        filteredRegistrars: regIds,
      },
      chartData: this.chartData,
      isAdmin: this.isAdmin,
      savedModel,
    };

    this.dialog.open(AiAnalysisModalComponent, {
      width: '800px',
      maxHeight: '90vh',
      data,
    });
  }

  private pageLabel(): string {
    switch (this.page) {
      case 'domain-activity':
        return 'Domain Activity';
      case 'revenue-billing':
        return 'Revenue Billing';
      case 'forecasting':
        return 'Forecasting';
      case 'explore':
        return 'Data Exploration';
      case 'overview':
        return 'Overview';
      case 'portfolio':
        return 'Portfolio';
      case 'pricing':
        return 'Pricing';
    }
  }
}
