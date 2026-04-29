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

import { Component } from '@angular/core';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { RegistryDashService } from '../registry-dash.service';
import { FilterPanelComponent } from '../filter-panel/filter-panel.component';
import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';
import { PORTFOLIO_PROMPTS } from '../ai/ai-prompts';

@Component({
  selector: 'app-registry-dash-portfolio',
  imports: [
    MaterialModule,
    CommonModule,
    FilterPanelComponent,
    AiSparkleButtonComponent,
  ],
  templateUrl: './portfolio.component.html',
  styleUrls: ['./portfolio.component.scss'],
})
export class PortfolioComponent {
  displayedColumns = [
    'registrarId',
    'registrarName',
    'state',
    'domainCount',
    'allowedTlds',
  ];

  readonly aiPrompts = PORTFOLIO_PROMPTS;

  constructor(protected dashService: RegistryDashService) {
    this.dashService.getPortfolio().subscribe();
  }
}
