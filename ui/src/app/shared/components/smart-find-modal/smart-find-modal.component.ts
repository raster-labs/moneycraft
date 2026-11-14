import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  effect,
  inject,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { IndianCurrencyPipe } from '../../../core/money/indian-currency.pipe';
import { CompanyContextStore } from '../../../state/company-context.store';
import { NavigationStackStore } from '../../../state/navigation-stack.store';
import { SmartFindResultItem, SmartFindStore } from '../../../state/smart-find.store';
import { CodeBadgeComponent } from '../code-badge/code-badge.component';

export interface HighlightSegment {
  readonly text: string;
  readonly matched: boolean;
}

export function splitHighlightSegments(
  snippet: string,
  query: string,
): readonly HighlightSegment[] {
  const needle = query.trim();
  if (!snippet || !needle) {
    return [{ text: snippet, matched: false }];
  }
  const lowerSnippet = snippet.toLowerCase();
  const lowerNeedle = needle.toLowerCase();
  const idx = lowerSnippet.indexOf(lowerNeedle);
  if (idx < 0) {
    return [{ text: snippet, matched: false }];
  }
  const segments: HighlightSegment[] = [];
  if (idx > 0) {
    segments.push({ text: snippet.slice(0, idx), matched: false });
  }
  segments.push({ text: snippet.slice(idx, idx + needle.length), matched: true });
  if (idx + needle.length < snippet.length) {
    segments.push({ text: snippet.slice(idx + needle.length), matched: false });
  }
  return segments;
}

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
        <div
          class="mc-modal-dialog mc-smartfind-dialog"
          data-testid="smartfind-dialog"
          (keydown)="onKeyDown($event)"
        >
          <div class="mc-modal-header">
            <span>
              SmartFind (<span class="mc-key-alt">F</span> / <span class="mc-key-ctrl">F</span>) — Masters, Vouchers, References &amp; Narrations
            </span>
            <button
              type="button"
              class="mc-scope-toggle-btn"
              data-testid="smartfind-scope-btn"
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
              data-testid="smartfind-query-input"
              [value]="smartFind.query()"
              (input)="onInput($event)"
              placeholder="Find Ledger, Voucher No, GSTIN, Narration, Bill Ref, Part No or Amount..."
              aria-label="SmartFind query"
            />
            @if (smartFind.query().trim().length > 0 && !smartFind.loading()) {
              <div class="mc-smartfind-stats">
                <span>{{ smartFind.totalMatches() }} match(es)</span>
                <span class="mc-code">{{ smartFind.tookMs() }} ms</span>
              </div>
            }
          </div>

          <div class="mc-smartfind-results" role="listbox">
            @for (group of smartFind.groupedCategories(); track group.category) {
              <div class="mc-smartfind-group-head" [attr.data-testid]="'smartfind-cat-' + group.category">
                <span>{{ group.label }}</span>
                <span class="mc-code">{{ group.entries.length }}</span>
              </div>

              @for (entry of group.entries; track entry.item.id) {
                <div
                  role="option"
                  class="mc-smartfind-row"
                  [class.mc-smartfind-row-active]="entry.flatIndex === smartFind.selectedIndex()"
                  [attr.aria-selected]="entry.flatIndex === smartFind.selectedIndex()"
                  [attr.data-testid]="'smartfind-row-' + entry.flatIndex"
                  (mouseenter)="smartFind.setSelectedIndex(entry.flatIndex)"
                  (click)="openResult(entry.item)"
                >
                  <div class="mc-smartfind-main">
                    <div class="mc-smartfind-title-line">
                      <span class="mc-badge mc-badge-slate">{{ entry.item.entityType }}</span>
                      <span class="mc-smartfind-title">{{ entry.item.title }}</span>
                      @if (entry.item.referenceCode) {
                        <mc-code-badge [code]="entry.item.referenceCode" />
                      }
                    </div>
                    <div class="mc-smartfind-sub">
                      {{ entry.item.companyName }} &bull; {{ entry.item.subtitle }} (Matched:
                      <strong>{{ entry.item.matchedField }}</strong>)
                    </div>
                    @if (entry.item.matchedSnippet) {
                      <div class="mc-smartfind-snippet" data-testid="smartfind-snippet">
                        @for (seg of highlightSnippet(entry.item.matchedSnippet); track $index) {
                          @if (seg.matched) {
                            <mark class="mc-smartfind-mark">{{ seg.text }}</mark>
                          } @else {
                            <span>{{ seg.text }}</span>
                          }
                        }
                      </div>
                    }
                  </div>
                  @if (entry.item.amount) {
                    <div class="mc-amount">{{ entry.item.amount | mcIndianCurrency }}</div>
                  }
                </div>
              }
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
              Enter = Open &bull; Alt+S = Toggle Company Scope &bull; Press Ctrl+F again for Browser Find
            </span>
            <button type="button" class="mc-btn" (click)="smartFind.close()">Close (Esc)</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-smartfind-dialog {
      width: min(720px, 95vw);
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
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .mc-smartfind-stats {
      display: flex;
      justify-content: space-between;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-slate-600);
    }

    .mc-smartfind-results {
      max-height: 360px;
      overflow-y: auto;
    }

    .mc-smartfind-group-head {
      padding: 0.3rem 0.875rem;
      background: var(--mc-color-slate-100);
      color: var(--mc-color-slate-700);
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: var(--mc-border-subtle);
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

    .mc-smartfind-snippet {
      margin-top: 2px;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-700);
      font-weight: 400;
    }

    .mc-smartfind-mark {
      background: rgba(76, 225, 189, 0.35);
      color: var(--mc-color-slate-900);
      font-weight: 700;
      padding: 0 2px;
      border-radius: 2px;
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
  private readonly companyContext = inject(CompanyContextStore);
  private readonly router = inject(Router);

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

  highlightSnippet(snippet: string): readonly HighlightSegment[] {
    return splitHighlightSegments(snippet, this.smartFind.query());
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

    const activeCompanyId = this.companyContext.activeCompany()?.id;
    const isDirty = this.companyContext.hasUnsavedDraft();

    if (item.companyId && item.companyId !== activeCompanyId && !isDirty) {
      this.companyContext.selectActiveCompany(item.companyId);
    }

    this.navStack.push({
      title: `${item.category}: ${item.title}`,
      route: item.route,
      params: isDirty
        ? { highlightId: item.id }
        : { highlightId: item.id, viaSmartFind: '1' },
    });

    if (!isDirty) {
      void this.router.navigateByUrl(item.route);
    }
  }

  onBackdropClick(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.smartFind.close();
    }
  }
}
