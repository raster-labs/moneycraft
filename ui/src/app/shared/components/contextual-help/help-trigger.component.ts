import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { SemanticIconComponent } from '../semantic-icon/semantic-icon.component';
import { ContextualHelpService } from './contextual-help.service';
import { HelpTooltipDirective } from './help-tooltip.directive';

/**
 * MoneyCraft `v1.1.0` — Accessible Contextual Help Trigger (`Doc 50 §7`, `WCAG 2.2 2.5.8`).
 *
 * - Enforces minimum `>= 24x24 CSS px` pointer/touch target (`28x28px` default).
 * - Exposes a meaningful accessible name (`"Help with <controlName>"`, never `"i"` or `"?"`).
 * - Shows short supplementary tooltip on hover/focus AND opens `HelpPanelComponent` on
 *   click, tap, `Enter`, or `Space` with exact focus restoration.
 */
@Component({
  selector: 'mc-help-trigger',
  standalone: true,
  imports: [SemanticIconComponent, HelpTooltipDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      #triggerBtn
      type="button"
      class="mc-help-trigger-btn"
      [mcHelpTooltip]="helpId()"
      [attr.aria-label]="accessibleLabel()"
      [attr.aria-expanded]="isExpanded()"
      aria-haspopup="dialog"
      [attr.data-testid]="'help-trigger-' + normalizedId()"
      (click)="openPanel($event)"
    >
      <mc-icon name="details" [size]="16" />
      @if (buttonText(); as text) {
        <span class="mc-help-trigger-text">{{ text }}</span>
      }
    </button>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      vertical-align: middle;
    }

    .mc-help-trigger-btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.25rem;
      min-width: 28px;
      min-height: 28px;
      padding: 0.2rem 0.35rem;
      border-radius: var(--mc-radius-sm, 4px);
      border: 1px solid var(--mc-color-slate-300);
      background: var(--mc-color-surface);
      color: var(--mc-color-deep-ledger-slate);
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-help-trigger-btn:hover {
      background: var(--mc-color-row-hover);
      border-color: var(--mc-color-verdigris-teal);
    }

    .mc-help-trigger-text {
      line-height: 1.2;
    }
  `,
})
export class HelpTriggerComponent {
  private readonly helpService = inject(ContextualHelpService);
  private readonly triggerBtnRef = viewChild<ElementRef<HTMLButtonElement>>('triggerBtn');

  readonly helpId = input.required<string>();
  readonly controlName = input<string | null>(null);
  readonly buttonText = input<string | null>(null);
  readonly showDirectory = input<boolean>(false);

  readonly entry = computed(() => this.helpService.resolveEntry(this.helpId()));

  readonly normalizedId = computed(() => this.entry()?.help_id ?? this.helpId());

  readonly resolvedTopicLabel = computed<string>(() => {
    const explicit = this.controlName();
    if (explicit && explicit.trim().length > 0) {
      return explicit.trim();
    }
    const found = this.entry();
    return found ? found.title.toLowerCase() : this.helpId();
  });

  readonly accessibleLabel = computed<string>(() => `Help with ${this.resolvedTopicLabel()}`);

  readonly isExpanded = computed<boolean>(() => {
    if (!this.helpService.panelOpen()) {
      return false;
    }
    const active = this.helpService.activePanelEntry();
    const current = this.entry();
    return Boolean(active && current && active.help_id === current.help_id);
  });

  openPanel(event: Event): void {
    event.stopPropagation();
    this.helpService.openHelpPanel(this.helpId(), {
      triggerElement: this.triggerBtnRef()?.nativeElement ?? null,
      controlName: this.resolvedTopicLabel(),
      showDirectory: this.showDirectory(),
    });
  }
}
