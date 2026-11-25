import { DOCUMENT } from '@angular/common';
import {
  Directive,
  ElementRef,
  HostListener,
  OnDestroy,
  Renderer2,
  inject,
  input,
} from '@angular/core';
import { ContextualHelpService } from './contextual-help.service';

let nextTooltipUid = 1;

/**
 * MoneyCraft `v1.1.0` — Supplementary Accessible Tooltip Directive (`Doc 50 §7`, `WCAG 2.2 1.4.13`).
 *
 * - Opens identical supplementary `short_text` on pointer hover AND keyboard focus without moving focus.
 * - Links trigger to popup via `aria-describedby` (never relies solely on HTML `title`).
 * - Hoverable: pointer can move from trigger onto the tooltip bubble without closing it.
 * - Persistent: zero reading timeout; remains visible until hover/focus ends or `Escape` dismisses it.
 * - Dismissible: `Escape` dismisses via `ContextualHelpService` (`window` capture) BEFORE any modal sees
 *   `Escape`, and suppresses automatic reopen until the user deliberately retriggers hover or focus.
 */
@Directive({
  selector: '[mcHelpTooltip]',
  standalone: true,
})
export class HelpTooltipDirective implements OnDestroy {
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);
  private readonly document = inject(DOCUMENT);
  private readonly helpService = inject(ContextualHelpService);

  readonly mcHelpTooltip = input.required<string>();
  readonly mcHelpTooltipText = input<string | null>(null);

  private readonly tooltipDomId = `mc-help-tooltip-${nextTooltipUid++}`;
  private popupElement: HTMLElement | null = null;
  private popupCleanupFns: (() => void)[] = [];
  private hoverLeaveTimer: ReturnType<typeof setTimeout> | null = null;

  private isHostHovered = false;
  private isPopupHovered = false;
  private isHostFocused = false;
  private dismissedUntilRetrigger = false;
  private originalAriaDescribedBy: string | null = null;

  @HostListener('mouseenter')
  onMouseEnter(): void {
    this.clearLeaveTimer();
    if (!this.isHostHovered && !this.isHostFocused) {
      this.dismissedUntilRetrigger = false;
    }
    this.isHostHovered = true;
    if (!this.dismissedUntilRetrigger) {
      this.showTooltip();
    }
  }

  @HostListener('mouseleave')
  onMouseLeave(): void {
    this.isHostHovered = false;
    this.scheduleHideCheck();
  }

  @HostListener('focusin')
  onFocusIn(): void {
    this.clearLeaveTimer();
    if (!this.isHostFocused && !this.isHostHovered) {
      this.dismissedUntilRetrigger = false;
    }
    this.isHostFocused = true;
    if (!this.dismissedUntilRetrigger) {
      this.showTooltip();
    }
  }

  @HostListener('focusout')
  onFocusOut(): void {
    this.isHostFocused = false;
    if (!this.isHostHovered && !this.isPopupHovered) {
      this.dismissedUntilRetrigger = false;
      this.hideTooltip();
    } else {
      this.scheduleHideCheck();
    }
  }

  ngOnDestroy(): void {
    this.clearLeaveTimer();
    this.hideTooltip();
  }

  private showTooltip(): void {
    const customText = this.mcHelpTooltipText();
    const entry = this.helpService.resolveEntry(this.mcHelpTooltip());
    const shortText = customText && customText.trim().length > 0 ? customText.trim() : entry?.short_text;
    if (!shortText) {
      return;
    }

    const title = entry?.title ?? 'Help';
    const helpId = entry?.help_id ?? this.mcHelpTooltip();

    if (!this.popupElement) {
      const popup = this.renderer.createElement('div') as HTMLElement;
      this.renderer.setAttribute(popup, 'id', this.tooltipDomId);
      this.renderer.setAttribute(popup, 'role', 'tooltip');
      this.renderer.setAttribute(popup, 'data-testid', `mc-tooltip-${helpId}`);
      this.renderer.addClass(popup, 'mc-contextual-tooltip');

      // Inline computed coordinates via Renderer2 (allowed in TS; zero interactive descendants per Doc 50 §7)
      this.renderer.setStyle(popup, 'position', 'fixed');
      this.renderer.setStyle(popup, 'z-index', 'var(--mc-z-help-tooltip, 960)');
      this.renderer.setStyle(popup, 'max-width', 'min(340px, calc(100vw - 16px))');
      this.renderer.setStyle(popup, 'padding', '0.45rem 0.65rem');
      this.renderer.setStyle(popup, 'border-radius', '4px');
      this.renderer.setStyle(popup, 'background', 'var(--mc-color-tooltip-bg, #142F42)');
      this.renderer.setStyle(popup, 'color', 'var(--mc-color-tooltip-fg, #FFFFFF)');
      this.renderer.setStyle(
        popup,
        'border',
        '1px solid var(--mc-color-tooltip-border, #4CE1BD)',
      );
      this.renderer.setStyle(popup, 'box-shadow', 'var(--mc-shadow-modal)');
      this.renderer.setStyle(popup, 'font-size', 'var(--mc-font-size-xs, 0.75rem)');
      this.renderer.setStyle(popup, 'font-weight', '500');
      this.renderer.setStyle(popup, 'line-height', '1.4');
      this.renderer.setStyle(popup, 'pointer-events', 'auto');

      const enterUnlisten = this.renderer.listen(popup, 'mouseenter', () => {
        this.clearLeaveTimer();
        this.isPopupHovered = true;
      });
      const leaveUnlisten = this.renderer.listen(popup, 'mouseleave', () => {
        this.isPopupHovered = false;
        this.scheduleHideCheck();
      });
      this.popupCleanupFns = [enterUnlisten, leaveUnlisten];

      this.renderer.appendChild(this.document.body, popup);
      this.popupElement = popup;
    }

    this.popupElement.textContent = shortText;
    this.positionPopupNearHost();
    this.attachAriaDescribedBy();

    this.helpService.registerActiveTooltip({
      tooltipDomId: this.tooltipDomId,
      helpId,
      title,
      shortText,
      dismissOnEscape: () => {
        this.dismissedUntilRetrigger = true;
        this.hideTooltip();
      },
    });
  }

  private hideTooltip(): void {
    this.clearLeaveTimer();
    this.isPopupHovered = false;
    if (this.popupElement) {
      for (const fn of this.popupCleanupFns) {
        fn();
      }
      this.popupCleanupFns = [];
      if (this.popupElement.parentNode) {
        this.renderer.removeChild(this.popupElement.parentNode, this.popupElement);
      }
      this.popupElement = null;
    }
    this.detachAriaDescribedBy();
    this.helpService.clearActiveTooltip(this.tooltipDomId);
  }

  private scheduleHideCheck(): void {
    this.clearLeaveTimer();
    this.hoverLeaveTimer = setTimeout(() => {
      if (!this.isHostHovered && !this.isPopupHovered && !this.isHostFocused) {
        this.dismissedUntilRetrigger = false;
        this.hideTooltip();
      }
    }, 120);
  }

  private clearLeaveTimer(): void {
    if (this.hoverLeaveTimer !== null) {
      clearTimeout(this.hoverLeaveTimer);
      this.hoverLeaveTimer = null;
    }
  }

  private positionPopupNearHost(): void {
    if (!this.popupElement) {
      return;
    }
    const hostRect = this.hostRef.nativeElement.getBoundingClientRect();
    const viewportWidth = this.document.defaultView?.innerWidth ?? 1024;
    const viewportHeight = this.document.defaultView?.innerHeight ?? 768;

    const topCandidate = hostRect.bottom + 6;
    const top =
      topCandidate + 72 > viewportHeight && hostRect.top > 80
        ? Math.max(8, hostRect.top - 64)
        : Math.max(8, topCandidate);
    const left = Math.max(8, Math.min(hostRect.left, viewportWidth - 348));

    this.renderer.setStyle(this.popupElement, 'top', `${Math.round(top)}px`);
    this.renderer.setStyle(this.popupElement, 'left', `${Math.round(left)}px`);
  }

  private attachAriaDescribedBy(): void {
    const host = this.hostRef.nativeElement;
    const current = host.getAttribute('aria-describedby');
    this.originalAriaDescribedBy = current;
    const tokens = current ? current.split(/\s+/).filter(Boolean) : [];
    if (!tokens.includes(this.tooltipDomId)) {
      tokens.push(this.tooltipDomId);
      this.renderer.setAttribute(host, 'aria-describedby', tokens.join(' '));
    }
  }

  private detachAriaDescribedBy(): void {
    const host = this.hostRef.nativeElement;
    const current = host.getAttribute('aria-describedby');
    if (!current) {
      return;
    }
    const remaining = current
      .split(/\s+/)
      .filter((t) => Boolean(t) && t !== this.tooltipDomId);
    if (remaining.length > 0) {
      this.renderer.setAttribute(host, 'aria-describedby', remaining.join(' '));
    } else if (this.originalAriaDescribedBy) {
      this.renderer.setAttribute(host, 'aria-describedby', this.originalAriaDescribedBy);
    } else {
      this.renderer.removeAttribute(host, 'aria-describedby');
    }
  }
}
