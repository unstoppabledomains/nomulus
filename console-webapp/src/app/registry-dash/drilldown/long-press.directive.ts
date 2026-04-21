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
