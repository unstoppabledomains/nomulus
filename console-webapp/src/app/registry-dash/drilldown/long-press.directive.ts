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

import { Directive, ElementRef, EventEmitter, OnDestroy, Output } from '@angular/core';

@Directive({
  selector: '[appLongPress]',
  standalone: true,
})
export class LongPressDirective implements OnDestroy {
  @Output() longPress = new EventEmitter<PointerEvent>();

  private timer: ReturnType<typeof setTimeout> | null = null;
  private startX = 0;
  private startY = 0;
  private fired = false;
  private readonly HOLD_MS = 500;
  private readonly MOVE_THRESHOLD = 10;

  constructor(private el: ElementRef<HTMLElement>) {
    const native = this.el.nativeElement;
    native.addEventListener('pointerdown', this.onPointerDown);
    native.addEventListener('pointerup', this.onPointerUp);
    native.addEventListener('pointercancel', this.onPointerUp);
    native.addEventListener('pointermove', this.onPointerMove);
    native.addEventListener('contextmenu', this.onContextMenu);
  }

  ngOnDestroy() {
    this.cancelTimer();
    const native = this.el.nativeElement;
    native.removeEventListener('pointerdown', this.onPointerDown);
    native.removeEventListener('pointerup', this.onPointerUp);
    native.removeEventListener('pointercancel', this.onPointerUp);
    native.removeEventListener('pointermove', this.onPointerMove);
    native.removeEventListener('contextmenu', this.onContextMenu);
  }

  private onPointerDown = (e: PointerEvent) => {
    this.startX = e.clientX;
    this.startY = e.clientY;
    this.fired = false;
    this.timer = setTimeout(() => {
      this.fired = true;
      this.longPress.emit(e);
      this.timer = null;
    }, this.HOLD_MS);
  };

  private onPointerUp = () => {
    this.cancelTimer();
  };

  private onPointerMove = (e: PointerEvent) => {
    if (this.timer) {
      const dx = Math.abs(e.clientX - this.startX);
      const dy = Math.abs(e.clientY - this.startY);
      if (dx > this.MOVE_THRESHOLD || dy > this.MOVE_THRESHOLD) {
        this.cancelTimer();
      }
    }
  };

  private onContextMenu = (e: Event) => {
    if (this.fired) {
      e.preventDefault();
      this.fired = false;
    }
  };

  private cancelTimer() {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
