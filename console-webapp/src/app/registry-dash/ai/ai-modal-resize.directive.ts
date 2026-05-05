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

import {
  Directive,
  ElementRef,
  EventEmitter,
  HostListener,
  OnDestroy,
  Output,
} from '@angular/core';

/**
 * Drag-handle directive for resizing the AI analysis modal.
 *
 * Design choice: the directive computes and emits ABSOLUTE width/height
 * values (clamped) rather than deltas. On mousedown it walks up from the
 * host element to find the closest positioned ancestor that represents
 * the dialog overlay (Material's `.mat-mdc-dialog-surface`, falling back
 * to the host's offsetParent), captures its starting bounding rect, and
 * then on mousemove emits new dimensions = startingSize + (mouseDelta).
 *
 * The consumer (modal component) is responsible for calling
 * `dialogRef.updateSize(...)` and persisting on `sizeCommit` (mouseup
 * only, fired exactly once per drag). This keeps the directive
 * dialog-agnostic while still working correctly inside Material's
 * overlay container.
 *
 * Clamps to:
 *   minWidth = 480, minHeight = 400
 *   maxWidth = window.innerWidth * 0.95, maxHeight = window.innerHeight * 0.95
 */
@Directive({
  selector: '[appAiModalResize]',
  standalone: true,
})
export class AiModalResizeDirective implements OnDestroy {
  @Output() sizeChange = new EventEmitter<{ width: number; height: number }>();
  @Output() sizeCommit = new EventEmitter<{ width: number; height: number }>();

  static readonly MIN_WIDTH = 480;
  static readonly MIN_HEIGHT = 400;

  private dragStart: {
    pointerX: number;
    pointerY: number;
    startWidth: number;
    startHeight: number;
  } | null = null;

  private lastSize: { width: number; height: number } | null = null;
  private prevBodyCursor = '';
  private prevBodyUserSelect = '';

  private moveHandler = (e: MouseEvent) => this.onMove(e);
  private upHandler = () => this.onUp();

  constructor(private host: ElementRef<HTMLElement>) {}

  @HostListener('mousedown', ['$event'])
  onDown(event: MouseEvent): void {
    if (event.button !== 0) return;
    const surface = this.findDialogSurface();
    if (!surface) return;

    event.preventDefault();
    event.stopPropagation();

    const rect = surface.getBoundingClientRect();
    this.dragStart = {
      pointerX: event.clientX,
      pointerY: event.clientY,
      startWidth: rect.width,
      startHeight: rect.height,
    };
    this.lastSize = { width: rect.width, height: rect.height };

    document.addEventListener('mousemove', this.moveHandler);
    document.addEventListener('mouseup', this.upHandler);

    this.prevBodyCursor = document.body.style.cursor;
    this.prevBodyUserSelect = document.body.style.userSelect;
    document.body.style.cursor = 'nwse-resize';
    // Prevent text selection while dragging.
    document.body.style.userSelect = 'none';
  }

  private onMove(event: MouseEvent): void {
    if (!this.dragStart) return;
    const dx = event.clientX - this.dragStart.pointerX;
    const dy = event.clientY - this.dragStart.pointerY;

    const maxW = window.innerWidth * 0.95;
    const maxH = window.innerHeight * 0.95;
    const width = Math.max(
      AiModalResizeDirective.MIN_WIDTH,
      Math.min(maxW, this.dragStart.startWidth + dx),
    );
    const height = Math.max(
      AiModalResizeDirective.MIN_HEIGHT,
      Math.min(maxH, this.dragStart.startHeight + dy),
    );

    this.lastSize = { width, height };
    this.sizeChange.emit({ width, height });
  }

  private onUp(): void {
    if (!this.dragStart) return;
    this.cleanupDragListeners();
    if (this.lastSize) {
      this.sizeCommit.emit(this.lastSize);
    }
    this.dragStart = null;
    this.lastSize = null;
  }

  private cleanupDragListeners(): void {
    document.removeEventListener('mousemove', this.moveHandler);
    document.removeEventListener('mouseup', this.upHandler);
    document.body.style.cursor = this.prevBodyCursor;
    document.body.style.userSelect = this.prevBodyUserSelect;
  }

  /**
   * Walk up from the host element to find the dialog surface element.
   * Prefers Material's `.mat-mdc-dialog-surface`; falls back to
   * `offsetParent`, then finally to the host's parentElement.
   */
  private findDialogSurface(): HTMLElement | null {
    let node: HTMLElement | null = this.host.nativeElement;
    while (node) {
      if (node.classList && node.classList.contains('mat-mdc-dialog-surface')) {
        return node;
      }
      node = node.parentElement;
    }
    const offsetParent = this.host.nativeElement.offsetParent as HTMLElement | null;
    return offsetParent || this.host.nativeElement.parentElement;
  }

  ngOnDestroy(): void {
    if (this.dragStart) {
      this.cleanupDragListeners();
      this.dragStart = null;
      this.lastSize = null;
    }
  }
}
