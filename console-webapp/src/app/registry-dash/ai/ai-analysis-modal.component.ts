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
  AiModelCatalog,
  AiModelChoice,
  ConversationMessage,
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
  static readonly SYSTEM_PROMPT_DRAFT_PREFIX = 'ai-system-prompt-draft:';
  selectedModel = signal<AiModelChoice>('sonnet');
  catalog = signal<AiModelCatalog | undefined>(undefined);
  /** Family shorthands ('haiku'/'sonnet'/'opus') currently available — others are hidden. */
  availableFamilies = computed<AiModelChoice[]>(() => {
    const c = this.catalog();
    if (!c) return ['haiku', 'sonnet', 'opus'];
    const out: AiModelChoice[] = [];
    if (c.haiku && c.haiku.length > 0) out.push('haiku');
    if (c.sonnet && c.sonnet.length > 0) out.push('sonnet');
    if (c.opus && c.opus.length > 0) out.push('opus');
    return out;
  });
  conversationHistory = computed(() => this.aiService.conversationHistory());
  followUpText = '';
  showAdvanced = signal(false);
  editableSystemPrompt = '';

  streaming = computed(() => this.aiService.streaming());
  streamedText = computed(() => this.aiService.streamedText());
  error = computed(() => this.aiService.error());
  toolsInFlight = computed(() => this.aiService.toolsInFlight());

  constructor(
    public dialogRef: MatDialogRef<AiAnalysisModalComponent>,
    @Inject(MAT_DIALOG_DATA) public data: AiAnalysisModalData,
    public aiService: AiAnalysisService,
    private dashService: RegistryDashService,
  ) {
    if (data.savedModel) {
      this.selectedModel.set(data.savedModel);
    }
    if (data.isAdmin) {
      // Pre-fill the textarea with this page's saved draft, but do NOT
      // auto-open the Advanced panel. The override only fires if the admin
      // explicitly toggles Advanced — this prevents a stale draft from
      // silently replacing the system prompt on next chat.
      const saved = localStorage.getItem(this.draftKey());
      if (saved) {
        this.editableSystemPrompt = saved;
      }
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
    this.dashService.getAiModelCatalog().subscribe((res) => {
      this.catalog.set(res.catalog);
      // If the user's saved/default selection is no longer available, fall back
      // to the first family that is.
      const families = this.availableFamilies();
      if (families.length > 0 && !families.includes(this.selectedModel())) {
        this.selectedModel.set(families[0]);
      }
    });
    // Cold-start "Ask anything" entries open with an empty `userMessage`.
    // In that case, do not auto-fire — the user types their first turn into
    // the follow-up input, which becomes the initial-request submitter via
    // `sendFollowUp()` (it builds the user turn from an empty history).
    if (!this.aiService.hasActiveConversation() && this.data.userMessage) {
      this.sendInitialRequest();
    }
  }

  /** True when the modal is open with no conversation yet — drives placeholder copy. */
  isColdStart = computed(() => this.conversationHistory().length === 0 && !this.streaming());

  /** Per-page draft key so a draft saved on one page never leaks into another. */
  private draftKey(): string {
    return AiAnalysisModalComponent.SYSTEM_PROMPT_DRAFT_PREFIX + this.data.page;
  }

  onSystemPromptChange(value: string) {
    this.editableSystemPrompt = value;
    if (this.data.isAdmin) {
      if (value) {
        localStorage.setItem(this.draftKey(), value);
      } else {
        localStorage.removeItem(this.draftKey());
      }
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
    // For cold-start "Ask anything" entries (empty seed), restart leaves the
    // modal idle so the user can type a fresh first turn.
    if (this.data.userMessage) {
      this.sendInitialRequest();
    }
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
