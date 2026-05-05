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

import { Component, ViewChild, ElementRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AiModalResizeDirective } from './ai-modal-resize.directive';

/**
 * Test host: simulates Material's `.mat-mdc-dialog-surface` ancestor
 * with a known starting size of 800x600. The drag handle is rendered
 * inside it.
 */
@Component({
  standalone: true,
  imports: [AiModalResizeDirective],
  template: `
    <div #surface class="mat-mdc-dialog-surface" style="position: absolute; left: 0; top: 0; width: 800px; height: 600px;">
      <div #handle
           class="handle"
           appAiModalResize
           (sizeChange)="onChange($event)"
           (sizeCommit)="onCommit($event)"></div>
    </div>
  `,
})
class HostComponent {
  @ViewChild('handle', { read: ElementRef }) handle!: ElementRef<HTMLElement>;
  @ViewChild('surface', { read: ElementRef }) surface!: ElementRef<HTMLElement>;
  changes: Array<{ width: number; height: number }> = [];
  commits: Array<{ width: number; height: number }> = [];

  onChange(size: { width: number; height: number }): void {
    this.changes.push(size);
  }
  onCommit(size: { width: number; height: number }): void {
    this.commits.push(size);
  }
}

function dispatchMouse(target: EventTarget, type: string, x: number, y: number): void {
  target.dispatchEvent(new MouseEvent(type, {
    clientX: x,
    clientY: y,
    button: 0,
    bubbles: true,
    cancelable: true,
  }));
}

describe('AiModalResizeDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
    // Mount the surface in the document so getBoundingClientRect() reports
    // real layout-derived dimensions matching the inline style.
    document.body.appendChild(host.surface.nativeElement);
  });

  afterEach(() => {
    if (host.surface?.nativeElement?.parentElement === document.body) {
      document.body.removeChild(host.surface.nativeElement);
    }
  });

  it('emits sizeChange multiple times during drag and sizeCommit exactly once on mouseup', () => {
    const handle = host.handle.nativeElement;
    const startRect = host.surface.nativeElement.getBoundingClientRect();
    // Use a starting pointer position large enough that the deltas don't
    // push us below MIN_WIDTH/MIN_HEIGHT (in case the karma DOM ends up
    // narrower than the inline styles request).
    const startX = 1000;
    const startY = 1000;
    const expectedFinalWidth = Math.max(
      AiModalResizeDirective.MIN_WIDTH,
      Math.min(window.innerWidth * 0.95, startRect.width + 50),
    );
    const expectedFinalHeight = Math.max(
      AiModalResizeDirective.MIN_HEIGHT,
      Math.min(window.innerHeight * 0.95, startRect.height + 50),
    );

    dispatchMouse(handle, 'mousedown', startX, startY);
    dispatchMouse(document, 'mousemove', startX + 10, startY + 10);
    dispatchMouse(document, 'mousemove', startX + 30, startY + 30);
    dispatchMouse(document, 'mousemove', startX + 50, startY + 50);
    dispatchMouse(document, 'mouseup', startX + 50, startY + 50);

    expect(host.changes.length).toBe(3);
    expect(host.commits.length).toBe(1);
    expect(host.commits[0].width).toBeCloseTo(expectedFinalWidth, 1);
    expect(host.commits[0].height).toBeCloseTo(expectedFinalHeight, 1);
    expect(host.changes[host.changes.length - 1]).toEqual(host.commits[0]);
  });

  it('clamps minimum width to 480 when dragging far left', () => {
    const handle = host.handle.nativeElement;

    dispatchMouse(handle, 'mousedown', 500, 500);
    // Drag far past the left edge: dx = -10000 → width would go negative.
    dispatchMouse(document, 'mousemove', -9500, 500);
    dispatchMouse(document, 'mouseup', -9500, 500);

    expect(host.commits.length).toBe(1);
    expect(host.commits[0].width).toBe(AiModalResizeDirective.MIN_WIDTH);
  });

  it('clamps minimum height to 400 when dragging far up', () => {
    const handle = host.handle.nativeElement;

    dispatchMouse(handle, 'mousedown', 500, 500);
    dispatchMouse(document, 'mousemove', 500, -9500);
    dispatchMouse(document, 'mouseup', 500, -9500);

    expect(host.commits.length).toBe(1);
    expect(host.commits[0].height).toBe(AiModalResizeDirective.MIN_HEIGHT);
  });

  it('clamps maximum width to 95vw when dragging far right', () => {
    const handle = host.handle.nativeElement;
    const maxW = window.innerWidth * 0.95;

    dispatchMouse(handle, 'mousedown', 0, 0);
    dispatchMouse(document, 'mousemove', 99999, 0);
    dispatchMouse(document, 'mouseup', 99999, 0);

    expect(host.commits.length).toBe(1);
    expect(host.commits[0].width).toBeCloseTo(maxW, 0);
  });

  it('does not emit sizeCommit if no mousedown occurred', () => {
    dispatchMouse(document, 'mouseup', 100, 100);
    expect(host.commits.length).toBe(0);
    expect(host.changes.length).toBe(0);
  });

  it('does not start drag on non-primary mouse button', () => {
    const handle = host.handle.nativeElement;

    handle.dispatchEvent(new MouseEvent('mousedown', {
      clientX: 100, clientY: 100, button: 2, bubbles: true, cancelable: true,
    }));
    dispatchMouse(document, 'mousemove', 200, 200);
    dispatchMouse(document, 'mouseup', 200, 200);

    expect(host.changes.length).toBe(0);
    expect(host.commits.length).toBe(0);
  });
});
