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

import { Component, Inject, computed, signal, OnInit, Pipe, PipeTransform } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { MaterialModule } from '../../material.module';
import { AiAnalysisService } from './ai-analysis.service';
import {
  AiAnalyzeRequest,
  AiModelChoice,
  ConversationMessage,
  TOOL_STATUS_CHIPS,
  ToolCompleted,
} from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';
import { marked } from 'marked';

@Pipe({ name: 'markdown', standalone: true })
export class MarkdownPipe implements PipeTransform {
  constructor(private sanitizer: DomSanitizer) {}
  transform(value: string): SafeHtml {
    if (!value) return '';
    const html = marked.parse(value, { async: false }) as string;
    return this.sanitizer.bypassSecurityTrustHtml(html);
  }
}

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
  imports: [CommonModule, MaterialModule, FormsModule, MarkdownPipe],
  templateUrl: './ai-analysis-modal.component.html',
  styleUrls: ['./ai-analysis-modal.component.scss'],
})
export class AiAnalysisModalComponent implements OnInit {
  selectedModel = signal<AiModelChoice>('sonnet');
  conversationHistory = computed(() => this.aiService.conversationHistory());
  followUpText = '';
  showAdvanced = signal(false);
  editableSystemPrompt = '';

  streaming = computed(() => this.aiService.streaming());
  streamedText = computed(() => this.aiService.streamedText());
  error = computed(() => this.aiService.error());
  toolsInFlight = computed(() => this.aiService.toolsInFlight());
  toolsCompleted = computed(() => this.aiService.toolsCompleted());

  /**
   * Returns the chip descriptor for a completed tool, or `null` when the
   * status is OK (silent — we don't render anything for happy paths). Used by
   * the template to drive a `*ngIf` and to pull chip text/tone.
   *
   * TODO(SRE-1958): no `ai-analysis-modal.component.spec.ts` exists today;
   * when one is added, cover chip rendering for at least one non-OK status
   * (e.g. EMPTY_FOR_RANGE, OUT_OF_RANGE) and assert the diagnostic flows into
   * the matTooltip binding.
   */
  chipFor(t: ToolCompleted): { text: string; tone: 'warn' | 'error' } | null {
    return TOOL_STATUS_CHIPS[t.status];
  }

  constructor(
    public dialogRef: MatDialogRef<AiAnalysisModalComponent>,
    @Inject(MAT_DIALOG_DATA) public data: AiAnalysisModalData,
    public aiService: AiAnalysisService,
    private dashService: RegistryDashService,
  ) {
    if (data.savedModel) {
      this.selectedModel.set(data.savedModel);
    }
  }

  ngOnInit() {
    // Clear any leftover transient state (e.g. an "interrupted" error from a
    // prior session) so a freshly opened modal never renders stale errors.
    // Conversation history is preserved here so a continued session can resume.
    // Use the stream-safe variant: `addToCurrentChat()` kicks off `analyze()`
    // (which sets `streaming=true`) BEFORE opening this dialog, so an
    // unconditional reset would flip `streaming` back to false mid-stream and
    // re-enable the follow-up input. A full reset (including pre-existing
    // history) on the sparkle-button path runs in that component's pre-open
    // `resetConversation()` call instead of via `afterClosed()`.
    this.aiService.clearStaleDisplayState();
    if (!this.aiService.hasActiveConversation()) {
      this.sendInitialRequest();
    }
  }

  private async sendInitialRequest() {
    const history: ConversationMessage[] = [
      { role: 'user', content: this.data.userMessage },
    ];

    await this.aiService.analyze({
      page: this.data.page,
      promptType: this.data.promptType,
      metadata: this.data.metadata,
      chartData: this.data.chartData,
      model: this.selectedModel(),
      systemPrompt: this.showAdvanced() ? this.editableSystemPrompt : undefined,
      conversationHistory: history,
    });
  }

  async sendFollowUp() {
    const input = this.followUpText.trim();
    if (!input || this.streaming()) return;

    const updatedHistory: ConversationMessage[] = [
      ...this.conversationHistory(),
      { role: 'user', content: input },
    ];
    this.followUpText = '';

    await this.aiService.analyze({
      page: this.data.page,
      promptType: this.data.promptType,
      metadata: this.data.metadata,
      chartData: this.data.chartData,
      model: this.selectedModel(),
      systemPrompt: this.showAdvanced() ? this.editableSystemPrompt : undefined,
      conversationHistory: updatedHistory,
    });
  }

  startNewChat() {
    this.aiService.resetConversation();
    this.sendInitialRequest();
  }

  onModelChange(model: AiModelChoice) {
    this.selectedModel.set(model);
    localStorage.setItem('ai-model-preference', model);
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
    if (this.showAdvanced() && !this.editableSystemPrompt) {
      this.editableSystemPrompt = this.data.systemPrompt ?? '';
    }
  }
}
