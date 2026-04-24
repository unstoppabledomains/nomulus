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

import { Component, Inject, computed, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MaterialModule } from '../../material.module';
import { AiAnalysisService } from './ai-analysis.service';
import { AiAnalyzeRequest, AiModelChoice, ConversationMessage } from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';

export interface AiAnalysisModalData {
  title: string;
  page: AiAnalyzeRequest['page'];
  promptType: string;
  userMessage: string;
  metadata: AiAnalyzeRequest['metadata'];
  chartData: any;
  systemPrompt?: string;
  isAdmin: boolean;
  savedModel?: AiModelChoice;
}

@Component({
  selector: 'app-ai-analysis-modal',
  standalone: true,
  imports: [CommonModule, MaterialModule, FormsModule],
  templateUrl: './ai-analysis-modal.component.html',
  styleUrls: ['./ai-analysis-modal.component.scss'],
})
export class AiAnalysisModalComponent implements OnInit {
  selectedModel = signal<AiModelChoice>('sonnet');
  conversationHistory = signal<ConversationMessage[]>([]);
  followUpInput = signal('');
  showAdvanced = signal(false);
  editableSystemPrompt = signal('');

  streaming = computed(() => this.aiService.streaming());
  streamedText = computed(() => this.aiService.streamedText());
  error = computed(() => this.aiService.error());

  constructor(
    public dialogRef: MatDialogRef<AiAnalysisModalComponent>,
    @Inject(MAT_DIALOG_DATA) public data: AiAnalysisModalData,
    private aiService: AiAnalysisService,
    private dashService: RegistryDashService,
  ) {
    if (data.savedModel) {
      this.selectedModel.set(data.savedModel);
    }
  }

  ngOnInit() {
    this.sendInitialRequest();
  }

  private async sendInitialRequest() {
    const history: ConversationMessage[] = [
      { role: 'user', content: this.data.userMessage },
    ];
    this.conversationHistory.set(history);

    await this.aiService.analyze({
      page: this.data.page,
      promptType: this.data.promptType,
      metadata: this.data.metadata,
      chartData: this.data.chartData,
      model: this.selectedModel(),
      systemPrompt: this.showAdvanced() ? this.editableSystemPrompt() : undefined,
      conversationHistory: history,
    });

    if (!this.error()) {
      this.conversationHistory.update(h => [
        ...h,
        { role: 'assistant', content: this.streamedText() },
      ]);
    }
  }

  async sendFollowUp() {
    const input = this.followUpInput().trim();
    if (!input || this.streaming()) return;

    const updatedHistory: ConversationMessage[] = [
      ...this.conversationHistory(),
      { role: 'user', content: input },
    ];
    this.conversationHistory.set(updatedHistory);
    this.followUpInput.set('');

    await this.aiService.analyze({
      page: this.data.page,
      promptType: this.data.promptType,
      metadata: this.data.metadata,
      chartData: this.data.chartData,
      model: this.selectedModel(),
      systemPrompt: this.showAdvanced() ? this.editableSystemPrompt() : undefined,
      conversationHistory: updatedHistory,
    });

    if (!this.error()) {
      this.conversationHistory.update(h => [
        ...h,
        { role: 'assistant', content: this.streamedText() },
      ]);
    }
  }

  onModelChange(model: AiModelChoice) {
    this.selectedModel.set(model);
    this.dashService.updateSettingsSelf({ aiModel: model }).subscribe();
  }

  onFollowUpKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendFollowUp();
    }
  }

  toggleAdvanced() {
    this.showAdvanced.update(v => !v);
    if (this.showAdvanced() && !this.editableSystemPrompt()) {
      this.editableSystemPrompt.set(this.data.systemPrompt ?? '');
    }
  }
}
