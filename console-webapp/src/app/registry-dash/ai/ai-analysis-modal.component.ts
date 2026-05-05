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

import { AfterViewInit, Component, ElementRef, Inject, ViewChild, computed, effect, signal, OnInit, Pipe, PipeTransform } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogConfig, MatDialogRef } from '@angular/material/dialog';
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
import { AiModalResizeDirective } from './ai-modal-resize.directive';
import { marked } from 'marked';

/**
 * localStorage keys for persisting the user's chosen AI modal size.
 */
export const AI_MODAL_WIDTH_KEY = 'ai-modal-width-px';
export const AI_MODAL_HEIGHT_KEY = 'ai-modal-height-px';

/**
 * Builds a MatDialogConfig for the AI analysis modal. Honors any
 * previously-persisted size in localStorage; otherwise defaults to
 * 960px wide x 85vh tall. Always caps at 95vw / 95vh.
 *
 * Single source of truth for all three dialog open call sites
 * (sparkle button + 2x explore).
 */
const MIN_W = 480;
const MIN_H = 400;

/**
 * Parses a persisted dimension from localStorage. Rejects non-finite
 * values (e.g. `Number('1e500') === Infinity`) and clamps below the
 * minimum reasonable size to prevent the modal from re-opening at a
 * sub-minimum value.
 */
function parseSavedDim(value: string | null, min: number): number | null {
  const n = Number(value);
  return Number.isFinite(n) && n >= min ? n : null;
}

export function aiModalConfig<T extends AiAnalysisModalData>(data: T): MatDialogConfig<T> {
  const savedW = parseSavedDim(localStorage.getItem(AI_MODAL_WIDTH_KEY), MIN_W);
  const savedH = parseSavedDim(localStorage.getItem(AI_MODAL_HEIGHT_KEY), MIN_H);
  const width = savedW !== null ? `${savedW}px` : '960px';
  const height = savedH !== null ? `${savedH}px` : '85vh';
  return {
    width,
    height,
    maxWidth: '95vw',
    maxHeight: '95vh',
    data,
  };
}

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
  imports: [CommonModule, MaterialModule, FormsModule, MarkdownPipe, AiModalResizeDirective],
  templateUrl: './ai-analysis-modal.component.html',
  styleUrls: ['./ai-analysis-modal.component.scss'],
})
export class AiAnalysisModalComponent implements OnInit, AfterViewInit {
  static readonly SYSTEM_PROMPT_DRAFT_PREFIX = 'ai-system-prompt-draft:';

  @ViewChild('scrollContainer') scrollContainer!: ElementRef<HTMLDivElement>;

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

  autoScrollEnabled = signal(true);
  showJumpToLatest = computed(() => !this.autoScrollEnabled() && this.streaming());
  private programmaticScrollGuard = false;

  // Sub-feature 3: prompt queue. Submitting while streaming pushes the
  // text onto pendingQueue; queued items fire serially after the active
  // response completes (auto-fire effect below). Stop sets isPaused so the
  // queue stays preserved without being drained until the user resumes.
  pendingQueue = signal<string[]>([]);
  pendingCount = computed(() => this.pendingQueue().length);
  isPaused = signal(false);
  // Synchronous guard preventing the auto-fire effect from re-firing
  // before the just-scheduled `runQueuedPrompt(head)` microtask runs and
  // flips `streaming` to true. Without it, the post-pop effect re-run in
  // the same tick sees streaming still false and would drain the rest of
  // the queue immediately.
  private firingInProgress = false;
  // Monotonic counter used to invalidate already-scheduled drain
  // microtasks. The auto-fire effect captures the current value before
  // queuing its microtask; the microtask only runs `runQueuedPrompt`
  // when its captured value still matches. `startNewChat()` bumps this,
  // which neutralizes any in-flight microtask so a stale queued head
  // can't fire into (and abort) the freshly-started chat.
  private drainGeneration = 0;

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

    // Reactively follow streaming output: when streamedText or
    // conversationHistory changes, scroll to bottom if user hasn't
    // scrolled away. Guarded against running before the view is ready.
    // Note: this also covers the streaming → idle transition because the
    // service updates conversationHistory (with the assistant turn) BEFORE
    // flipping streaming to false. On cancel, neither streamedText nor
    // conversationHistory updates — desirable, since an interrupting user
    // may have intentionally scrolled up.
    effect(() => {
      // Track these signals so the effect re-runs on change.
      this.streamedText();
      this.conversationHistory();
      if (!this.scrollContainer) return;
      if (this.autoScrollEnabled()) {
        requestAnimationFrame(() => this.scrollToBottom());
      }
    });

    // Sub-feature 3: auto-fire queued prompts. Re-runs whenever streaming,
    // error, paused, or queue changes. Returns early on every "blocked"
    // condition so it never recurses or infinite-loops:
    //  - streaming → wait for completion
    //  - error → wait for user to click Retry
    //  - paused → wait for user to click Resume (Stop scenario)
    //  - empty queue → nothing to do
    //  - firingInProgress → already scheduled a head-fire this tick;
    //    avoids draining the entire queue before `analyze()` flips
    //    `streaming` to true (the gate that normally throttles firing).
    // When firing, we pop the head synchronously (so a re-run in the same
    // tick sees an empty queue) and defer the actual `analyze()` call to a
    // microtask to avoid touching signals while another effect is still
    // resolving.
    effect(() => {
      const isStreaming = this.streaming();
      const hasError = !!this.error();
      const queue = this.pendingQueue();
      if (this.isPaused()) return;
      if (isStreaming) return;
      if (hasError) return;
      if (queue.length === 0) return;
      if (this.firingInProgress) return;
      const [head, ...rest] = queue;
      this.firingInProgress = true;
      this.pendingQueue.set(rest);
      // Capture the generation; if startNewChat() (or any other reset)
      // bumps `drainGeneration` before this microtask runs, the captured
      // gen no longer matches and we skip firing the now-stale head.
      const gen = ++this.drainGeneration;
      queueMicrotask(() => {
        this.firingInProgress = false;
        if (gen !== this.drainGeneration) return;
        this.runQueuedPrompt(head);
      });
    });
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
    if (!this.aiService.hasActiveConversation()) {
      this.sendInitialRequest();
    }
  }

  ngAfterViewInit(): void {
    // The auto-scroll effect runs once at construction time, before
    // @ViewChild('scrollContainer') resolves, and exits early. If the
    // dialog is opened around an already-active chat (e.g. addToCurrentChat
    // — which calls analyze() and may have populated conversationHistory
    // before the modal mounts), the effect won't re-run until the next
    // streamedText/conversationHistory tick, leaving the user looking at
    // the top of the transcript. Pin to bottom now that the view is ready.
    if (this.conversationHistory().length > 0 || this.streamedText()) {
      requestAnimationFrame(() => this.scrollToBottom());
    }
  }

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

  /**
   * Thin wrapper for the template's send-button click. Routes through
   * `enqueueOrSend` so the streaming-vs-idle decision lives in one place.
   */
  sendFollowUp(): void {
    this.enqueueOrSend(this.followUpText);
  }

  /**
   * Single entry point for new prompt submissions. While the AI is still
   * responding (or the queue is paused after a Stop), the prompt is
   * appended to `pendingQueue` instead of firing immediately; the
   * auto-fire effect drains the queue serially once the response
   * completes. Otherwise the prompt is sent immediately.
   */
  enqueueOrSend(text: string): void {
    const input = text.trim();
    if (!input) return;
    if (this.streaming() || this.isPaused()) {
      this.pendingQueue.update(q => [...q, input]);
      this.followUpText = '';
      return;
    }
    this.followUpText = '';
    void this.runQueuedPrompt(input);
  }

  /**
   * Send a prompt whose text is supplied directly (i.e. NOT read from
   * `followUpText`). Used both for the immediate-send path and for the
   * auto-fire effect that drains the queue.
   */
  private async runQueuedPrompt(text: string): Promise<void> {
    const updatedHistory: ConversationMessage[] = [
      ...this.conversationHistory(),
      { role: 'user', content: text },
    ];

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

  removeQueued(idx: number): void {
    this.pendingQueue.update(q => q.filter((_, i) => i !== idx));
  }

  /**
   * Hoist a queued prompt back into the input for editing. The chip is
   * removed so the user doesn't end up with a duplicate after re-sending.
   */
  editQueued(idx: number): void {
    const item = this.pendingQueue()[idx];
    if (item === undefined) return;
    this.followUpText = item;
    this.removeQueued(idx);
  }

  resumeQueue(): void {
    this.isPaused.set(false);
    // Auto-fire effect picks up the remaining queue.
  }

  /**
   * Clears both the paused gate AND the error signal so the auto-fire
   * effect can proceed. We clear `error` here (rather than relying on
   * analyze()) because the effect reads `error()` BEFORE firing — without
   * an explicit clear the effect would short-circuit.
   */
  retryAfterError(): void {
    this.isPaused.set(false);
    this.aiService.clearError();
  }

  startNewChat() {
    this.pendingQueue.set([]);
    this.isPaused.set(false);
    // Re-engage auto-scroll for the fresh conversation. Without this, a
    // chat where the user had scrolled up before clicking "Start new chat"
    // would inherit `autoScrollEnabled === false`, and streaming output
    // for the new conversation would not pin to the bottom.
    this.autoScrollEnabled.set(true);
    // Invalidate any in-flight auto-fire microtask: the effect already
    // synchronously popped a queue head and scheduled a microtask before
    // we got here. Bumping drainGeneration (and clearing firingInProgress
    // for good measure) ensures that microtask becomes a no-op instead
    // of firing the stale head into (and aborting) the new chat.
    this.firingInProgress = false;
    this.drainGeneration++;
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
      this.enqueueOrSend(this.followUpText);
    }
  }

  onStop(): void {
    this.aiService.cancel();
    // Preserve the queue but prevent the auto-fire effect from draining
    // it once cancel() flips streaming → false. The user can hit Resume
    // to restart, or remove/edit chips first.
    if (this.pendingQueue().length > 0) {
      this.isPaused.set(true);
    }
  }

  toggleAdvanced() {
    this.showAdvanced.update(v => !v);
    if (this.showAdvanced() && !this.editableSystemPrompt) {
      this.editableSystemPrompt = this.data.systemPrompt ?? '';
    }
  }

  onConversationScroll(): void {
    if (this.programmaticScrollGuard) return;
    if (!this.scrollContainer) return;
    const el = this.scrollContainer.nativeElement;
    const atBottom = (el.scrollHeight - el.scrollTop - el.clientHeight) <= 40;
    this.autoScrollEnabled.set(atBottom);
  }

  jumpToLatest(): void {
    this.autoScrollEnabled.set(true);
    this.scrollToBottom();
  }

  /**
   * Live-update the dialog size as the user drags the resize handle.
   * Fires continuously during drag — no localStorage write here.
   */
  onModalResize(size: { width: number; height: number }): void {
    this.dialogRef.updateSize(`${Math.round(size.width)}px`, `${Math.round(size.height)}px`);
  }

  /**
   * Commit the chosen size to localStorage. Fires exactly once per drag,
   * on mouseup. Subsequent dialog opens will pick this up via
   * aiModalConfig().
   */
  onModalResizeCommit(size: { width: number; height: number }): void {
    localStorage.setItem(AI_MODAL_WIDTH_KEY, String(Math.round(size.width)));
    localStorage.setItem(AI_MODAL_HEIGHT_KEY, String(Math.round(size.height)));
  }

  private scrollToBottom(): void {
    if (!this.scrollContainer) return;
    this.programmaticScrollGuard = true;
    const el = this.scrollContainer.nativeElement;
    el.scrollTop = el.scrollHeight;
    requestAnimationFrame(() => {
      this.programmaticScrollGuard = false;
    });
  }
}
