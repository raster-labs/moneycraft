import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RUPEE_SYMBOL } from '../../../core/money/exact-decimal';

@Component({
  selector: 'mc-code-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="mc-code mc-code-pill" [attr.title]="safeCode()">{{ safeCode() }}</span>
  `,
  styles: `
    .mc-code-pill {
      display: inline-flex;
      align-items: center;
      padding: 0.1rem 0.4rem;
      border-radius: var(--mc-radius-xs);
      background: var(--mc-color-slate-100);
      border: 1px solid var(--mc-color-slate-200);
      color: var(--mc-color-slate-900);
      font-size: var(--mc-font-size-xs);
      line-height: 1.3;
      user-select: all;
    }
  `,
})
export class CodeBadgeComponent {
  readonly code = input.required<string>();

  /**
   * Brand Identity v0.2.0 invariant: NEVER render U+20B9 (`₹`) inside `.mc-code`
   * because JetBrains Mono 2.304 lacks the U+20B9 glyph.
   */
  readonly safeCode = computed(() => this.code().replaceAll(RUPEE_SYMBOL, 'INR '));
}
