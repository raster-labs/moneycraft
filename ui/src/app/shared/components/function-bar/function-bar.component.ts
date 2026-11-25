import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { KeycapModifierStyle } from '../../../core/keyboard/shortcut-command.registry';

export interface FunctionBarButtonItem {
  readonly commandId: string;
  readonly keycap: string;
  readonly modifierStyle: KeycapModifierStyle;
  readonly label: string;
  readonly disabled?: boolean;
  readonly active?: boolean;
}

@Component({
  selector: 'mc-function-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (orientation() === 'vertical') {
      <aside class="mc-fbar-rail mc-no-print" aria-label="Function Key Rail (F1 to F12)">
        @for (item of items(); track item.commandId) {
          <button
            type="button"
            class="mc-fbar-btn"
            [attr.data-testid]="'fn-btn-' + item.commandId"
            [class.mc-fbar-btn-active]="item.active"
            [disabled]="item.disabled"
            (click)="commandTriggered.emit(item.commandId)"
          >
            <span
              class="mc-fbar-key"
              [class.mc-key-alt]="item.modifierStyle === 'ALT_SINGLE_UNDERLINE'"
              [class.mc-key-ctrl]="item.modifierStyle === 'CTRL_DOUBLE_UNDERLINE'"
            >
              {{ item.keycap }}
            </span>
            <span class="mc-fbar-sep">:</span>
            <span class="mc-fbar-label">{{ item.label }}</span>
          </button>
        }
      </aside>
    } @else {
      <footer class="mc-bottom-bar mc-no-print" aria-label="Bottom Action Shortcut Bar">
        <div class="mc-bottom-actions">
          @for (item of items(); track item.commandId) {
            <button
              type="button"
              class="mc-bottom-btn"
              [class.mc-bottom-btn-active]="item.active"
              [disabled]="item.disabled"
              (click)="commandTriggered.emit(item.commandId)"
            >
              <span
                class="mc-bottom-key"
                [class.mc-key-alt]="item.modifierStyle === 'ALT_SINGLE_UNDERLINE'"
                [class.mc-key-ctrl]="item.modifierStyle === 'CTRL_DOUBLE_UNDERLINE'"
              >
                {{ item.keycap }}
              </span>
              <span>: {{ item.label }}</span>
            </button>
          }
        </div>
        <div class="mc-bottom-meta">
          <span>Single underline = Alt &bull; Double underline = Ctrl</span>
        </div>
      </footer>
    }
  `,
  styles: `
    .mc-fbar-rail {
      width: var(--mc-shell-fbar-width);
      background: var(--mc-color-slate-100);
      border-left: var(--mc-border-strong);
      padding: var(--mc-space-1);
      display: flex;
      flex-direction: column;
      gap: 3px;
      height: 100%;
      overflow-y: auto;
    }

    .mc-fbar-btn {
      width: 100%;
      min-height: 28px;
      padding: 0.2rem 0.45rem;
      background: var(--mc-color-surface);
      border: 1px solid var(--mc-color-slate-200);
      border-radius: var(--mc-radius-xs);
      color: var(--mc-color-slate-900);
      display: flex;
      align-items: center;
      text-align: left;
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      cursor: pointer;

      &:hover:not(:disabled) {
        background: var(--mc-color-teal-50);
        border-color: var(--mc-color-verdigris-teal);
      }

      &:disabled {
        background: var(--mc-color-cool-alabaster);
        color: var(--mc-color-deep-ledger-slate);
        border: 1px dashed #142F42;
        opacity: 1;
        cursor: not-allowed;
      }
    }

    .mc-fbar-btn-active {
      background: var(--mc-color-teal-100);
      border-color: var(--mc-color-teal-700);
      font-weight: 600;
    }

    .mc-fbar-key {
      min-width: 24px;
      color: var(--mc-color-teal-700);
      font-weight: 700;
    }

    .mc-fbar-sep {
      margin-right: 4px;
      color: var(--mc-color-slate-500);
    }

    .mc-fbar-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .mc-bottom-bar {
      min-height: var(--mc-shell-bottombar-height);
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      padding: 0.15rem 0.75rem;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
    }

    .mc-bottom-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 4px;
    }

    .mc-bottom-btn {
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid rgba(255, 255, 255, 0.18);
      border-radius: var(--mc-radius-xs);
      color: var(--mc-color-surface);
      padding: 0.1rem 0.5rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 500;
      cursor: pointer;

      &:hover:not(:disabled) {
        background: var(--mc-color-verdigris-teal);
        border-color: var(--mc-color-mint-signal);
      }

      &:disabled {
        border: 1px dashed rgba(255, 255, 255, 0.65);
        opacity: 1;
        cursor: not-allowed;
      }
    }

    .mc-bottom-btn-active {
      background: var(--mc-color-pressed-teal);
      border-color: var(--mc-color-mint-signal);
    }

    .mc-bottom-key {
      color: var(--mc-color-mint-signal);
      font-weight: 700;
    }

    .mc-bottom-meta {
      color: var(--mc-color-slate-300);
      font-weight: 500;
      white-space: nowrap;
    }
  `,
})
export class FunctionBarComponent {
  readonly orientation = input<'vertical' | 'horizontal'>('vertical');
  readonly items = input.required<readonly FunctionBarButtonItem[]>();
  readonly commandTriggered = output<string>();
}
