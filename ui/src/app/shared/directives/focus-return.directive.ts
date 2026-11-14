import { DOCUMENT } from '@angular/common';
import { AfterViewInit, Directive, ElementRef, OnDestroy, inject, input } from '@angular/core';

@Directive({
  selector: '[mcFocusReturn]',
  standalone: true,
})
export class FocusReturnDirective implements AfterViewInit, OnDestroy {
  private readonly document = inject(DOCUMENT);
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly mcFocusReturnAutoFocus = input<boolean>(true);
  private previousElement: HTMLElement | null = null;

  ngAfterViewInit(): void {
    const active = this.document.activeElement;
    if (active instanceof HTMLElement && active !== this.document.body) {
      this.previousElement = active;
    }

    if (this.mcFocusReturnAutoFocus()) {
      const firstFocusable = this.hostRef.nativeElement.querySelector<HTMLElement>(
        '[data-mc-autofocus], input:not([disabled]), select:not([disabled]), button:not([disabled])',
      );
      (firstFocusable ?? this.hostRef.nativeElement).focus({ preventScroll: true });
    }
  }

  ngOnDestroy(): void {
    const target = this.previousElement;
    if (target && this.document.contains(target)) {
      target.focus({ preventScroll: true });
    }
  }
}
