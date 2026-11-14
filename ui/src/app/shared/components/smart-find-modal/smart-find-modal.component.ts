import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  viewChild,
} from '@angular/core';
import { IndianCurrencyPipe } from '../../../core/money/indian-currency.pipe';
import { NavigationStackStore } from '../../../state/navigation-stack.store';
import { SmartFindResultItem, SmartFindStore } from '../../../state/smart-find.store';
import { CodeBadgeComponent } from '../code-badge/code-badge.component';

@Component({
  selector: 'mc-smart-find-modal',
  standalone: true,
  imports: [CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (smartFind.isOpen()) {
      <div
        class="mc-modal-backdrop"
        role="dialog"
        aria-modal="true"
        aria-label="SmartFind Multi-Company Search"
        (click)="onBackdropClick($event)"
      >
        <div class="mc-modal-dialog mc-smartfind-dialog" (keydown)="onKeyDown($event)">
          <div class="mc-modal-header">
            <span>
              SmartFind (<span class="mc-key-alt">F</span> / <span class="mc-key-ctrl">F</span>) — Masters, Vouchers, References &amp; Narrations
            </span>
            <button
              type="button"
              class="mc-scope-toggle-btn"
              (click)="smartFind.toggleScope()"
            >
              Scope:
              @if (smartFind.scope() === 'ALL_OPEN_COMPANIES') {
                All Open Companies
              } @else {
                Active Company
              }
              (Alt+S)
            </button>
          </div>

          <div class="mc-smartfind-input-bar">
            <input
              #queryInput
              type="text"
              class="mc-input"
              [value]="smartFind.query()"
              (input)="onInput($event)"
              placeholder="Find Ledger, Voucher No, GSTIN, Narration, Bill Ref, Part No or Amount..."
              aria-label="SmartFind query"
            />
          </div>

          <div class="mc-smartfind-results" role="listbox">
            @for (item of smartFind.results(); track item.id; let idx = $index) {
              <div
                role="option"
                class="mc-smartfind-row"
                [class.mc-smartfind-row-active]="idx === smartFind.selectedIndex()"
                [attr.aria-selected]="idx === smartFind.selectedIndex()"
                (click)="openResult(item)"
              >
                <div class="mc-smartfind-main">
                  <div class="mc-smartfind-title-line">
                    <span class="mc-badge mc-badge-slate">{{ item.category }}</span>
                    <span class="mc-smartfind-title">{{ item.title }}</span>
                    @if (item.referenceCode) {
                      <mc-code-badge [code]="item.referenceCode" />
                    }
                  </div>
                  <div class="mc-smartfind-sub">
                    {{ item.companyName }} &bull; {{ item.subtitle }} (Matched: {{ item.matchedField }})
                  </div>
                </div>
                @if (item.amountWire) {
                  <div class="mc-amount">{{ item.amountWire | mcIndianCurrency }}</div>
                }
              </div>
            } @empty {
              <div class="mc-smartfind-empty">
                @if (smartFind.query().trim().length === 0) {
                  Type any reference, GSTIN, ledger name, narration, or amount to search instantly.
                } @else if (smartFind.loading()) {
                  Searching books...
                } @else {
                  No matching masters or transactions found for "{{ smartFind.query() }}".
                }
              </div>
            }
          </div>

          <div class="mc-modal-footer">
            <span class="mc-smartfind-hint">
              Enter = Open on Go To Stack &bull; Alt+S = Toggle Company Scope &bull; Press Ctrl+F again for Browser Find
            </span>
            <button type="button" class="mc-btn" (click)="smartFind.close()">Close (Esc)</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-smartfind-dialog {
      width: min(680px, 95vw);
    }

    .mc-scope-toggle-btn {
      background: rgba(76, 225, 189, 0.14);
      border: 1px solid var(--mc-color-mint-signal);
      color: var(--mc-color-mint-signal);
      border-radius: var(--mc-radius-xs);
      padding: 0.15rem 0.55rem;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      cursor: pointer;
    }

    .mc-smartfind-input-bar {
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border-bottom: var(--mc-border-subtle);
    }

    .mc-smartfind-results {
      max-height: 340px;
      overflow-y: auto;
    }

    .mc-smartfind-row {
      padding: 0.5rem 0.875rem;
      border-bottom: 1px solid var(--mc-color-slate-100);
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      cursor: pointer;
    }

    .mc-smartfind-row-active {
      background: var(--mc-color-row-selected);
      border-left: 3px solid var(--mc-color-verdigris-teal);
    }

    .mc-smartfind-title-line {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      font-weight: 600;
    }

    .mc-smartfind-sub {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      margin-top: 2px;
      font-weight: 500;
    }

    .mc-smartfind-empty {
      padding: var(--mc-space-5);
      text-align: center;
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }

    .mc-smartfind-hint {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      font-weight: 500;
    }
  `,
})
export class SmartFindModalComponent {
  readonly smartFind = inject(SmartFindStore);
  private readonly navStack = inject(NavigationStackStore);

  private readonly queryInputRef = viewChild<ElementRef<HTMLInputElement>>('queryInput');

  constructor() {
    effect(() => {
      if (this.smartFind.isOpen()) {
        queueMicrotask(() => {
          const el = this.queryInputRef()?.nativeElement;
          if (el) {
            el.focus();
            el.select();
          }
        });
      }
    });
  }

  onInput(event: Event): void {
    const val = (event.target as HTMLInputElement).value;
    void this.smartFind.search(val);
  }

  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.smartFind.close();
      return;
    }

    if (
      event.altKey &&
      !event.ctrlKey &&
      (event.code === 'KeyS' || event.key.toLowerCase() === 's')
    ) {
      event.preventDefault();
      this.smartFind.toggleScope();
      return;
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.smartFind.moveSelection(1);
      return;
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.smartFind.moveSelection(-1);
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      const chosen = this.smartFind.selectedResult();
      if (chosen) {
        this.openResult(chosen);
      }
    }
  }

  openResult(item: SmartFindResultItem): void {
    this.smartFind.close();
    this.navStack.push({
      title: `${item.category}: ${item.title}`,
      route: item.targetRoute,
      params: { highlightId: item.id },
    });
  }

  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.smartFind.close();
    }
  }
}
