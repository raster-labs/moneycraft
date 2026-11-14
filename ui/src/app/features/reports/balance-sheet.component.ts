import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import {
  BalanceSheetFormat,
  BalanceSheetReportDto,
  FinancialStatementLineDto,
} from './reports.models';
import { ReportsService } from './reports.service';

@Component({
  selector: 'mc-balance-sheet',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bs-page">
      <header class="mc-card bs-header">
        <div>
          <span class="bs-pill">Financial Statements &bull; Balance Sheet</span>
          <h1>Balance Sheet</h1>
          <p class="bs-sub">
            As at <span class="mc-code">{{ asOfDate() }}</span> &bull;
            {{ format() === 'HORIZONTAL' ? 'Horizontal Two-Column (Ctrl+H = Vertical)' : 'Vertical Statement (Ctrl+H = Horizontal)' }}
            &bull; {{ detailedMode() ? 'Detailed (Alt+F1)' : 'Condensed (Alt+F1)' }}
          </p>
        </div>

        <div class="bs-actions">
          <button
            type="button"
            class="mc-btn"
            data-testid="bs-toggle-format-btn"
            (click)="toggleFormat()"
          >
            Ctrl+H: {{ format() === 'HORIZONTAL' ? 'Vertical' : 'Horizontal' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="bs-toggle-detailed-btn"
            (click)="detailedMode.set(!detailedMode())"
          >
            Alt+F1: {{ detailedMode() ? 'Condensed' : 'Detailed' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="bs-auto-column-btn"
            [class.mc-btn-primary]="autoColumnar()"
            (click)="autoColumnar.set(!autoColumnar())"
          >
            Alt+N: Auto-Column
          </button>
          <button type="button" class="mc-btn" (click)="toggleOptional()">
            Ctrl+B: {{ includeOptional() ? 'Postings Only' : 'Incl. Optional' }}
          </button>
          <button type="button" class="mc-btn" (click)="openDateModal()">
            F2: As-of Date
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="bs-print-pdf-btn"
            (click)="exportPdf()"
          >
            Print PDF (Ctrl+P)
          </button>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="bs-banner" data-testid="bs-status-banner" role="status">{{ msg }}</div>
      }

      @if (loading()) {
        <div class="mc-card state-box">Computing Balance Sheet assets &amp; liabilities...</div>
      } @else if (report(); as r) {
        @if (format() === 'HORIZONTAL') {
          <section class="mc-card bs-card" data-testid="bs-horizontal-view">
            <div class="bs-two-col">
              <!-- Left Column: Liabilities -->
              <div class="bs-col bs-col-left">
                <div class="bs-col-head">
                  <span>Liabilities</span>
                  <div class="amt-heads">
                    @if (autoColumnar()) {
                      <span>Comparative</span>
                    }
                    <span>As at {{ r.asOfDate }}</span>
                  </div>
                </div>
                <ul class="bs-list">
                  @for (line of r.liabilityLines; track line.id + '-' + line.name) {
                    <li
                      class="bs-row"
                      [class.bs-row-active]="selectedKey() === 'L:' + line.id"
                      (click)="selectedKey.set('L:' + line.id)"
                      (dblclick)="drillLine(line)"
                    >
                      <div class="bs-row-main">
                        <strong>{{ line.name }}</strong>
                        <div class="amt-pair">
                          @if (autoColumnar()) {
                            <span class="mc-amount muted-amt">
                              {{ (line.comparativeAmount ?? '0.00') | mcIndianCurrency }}
                            </span>
                          }
                          <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                        </div>
                      </div>
                      @if (detailedMode() && line.children.length > 0) {
                        <ul class="bs-sublist">
                          @for (sub of line.children; track sub.id + '-' + sub.name) {
                            <li class="bs-subrow" (dblclick)="drillLine(sub)">
                              <span>{{ sub.name }}</span>
                              <span class="mc-amount">{{ sub.amount | mcIndianCurrency }}</span>
                            </li>
                          }
                        </ul>
                      }
                    </li>
                  }
                </ul>
                <div class="bs-col-total">
                  <strong>Total Liabilities</strong>
                  <strong class="mc-amount" data-testid="bs-total-liabilities">
                    {{ r.totalLiabilities | mcIndianCurrency }}
                  </strong>
                </div>
              </div>

              <!-- Right Column: Assets -->
              <div class="bs-col">
                <div class="bs-col-head">
                  <span>Assets</span>
                  <div class="amt-heads">
                    @if (autoColumnar()) {
                      <span>Comparative</span>
                    }
                    <span>As at {{ r.asOfDate }}</span>
                  </div>
                </div>
                <ul class="bs-list">
                  @for (line of r.assetLines; track line.id + '-' + line.name) {
                    <li
                      class="bs-row"
                      [class.bs-row-active]="selectedKey() === 'A:' + line.id"
                      (click)="selectedKey.set('A:' + line.id)"
                      (dblclick)="drillLine(line)"
                    >
                      <div class="bs-row-main">
                        <strong>{{ line.name }}</strong>
                        <div class="amt-pair">
                          @if (autoColumnar()) {
                            <span class="mc-amount muted-amt">
                              {{ (line.comparativeAmount ?? '0.00') | mcIndianCurrency }}
                            </span>
                          }
                          <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                        </div>
                      </div>
                      @if (detailedMode() && line.children.length > 0) {
                        <ul class="bs-sublist">
                          @for (sub of line.children; track sub.id + '-' + sub.name) {
                            <li class="bs-subrow" (dblclick)="drillLine(sub)">
                              <span>{{ sub.name }}</span>
                              <span class="mc-amount">{{ sub.amount | mcIndianCurrency }}</span>
                            </li>
                          }
                        </ul>
                      }
                    </li>
                  }
                </ul>
                <div class="bs-col-total">
                  <strong>Total Assets</strong>
                  <strong class="mc-amount" data-testid="bs-total-assets">
                    {{ r.totalAssets | mcIndianCurrency }}
                  </strong>
                </div>
              </div>
            </div>
          </section>
        } @else {
          <!-- Vertical Statement Format (Ctrl+H) -->
          <section class="mc-card bs-card" data-testid="bs-vertical-view">
            <div class="vertical-section-title">I. Sources of Funds (Equity &amp; Liabilities)</div>
            <ul class="bs-list">
              @for (line of r.liabilityLines; track line.id + '-' + line.name) {
                <li
                  class="bs-row"
                  [class.bs-row-active]="selectedKey() === 'L:' + line.id"
                  (click)="selectedKey.set('L:' + line.id)"
                  (dblclick)="drillLine(line)"
                >
                  <div class="bs-row-main">
                    <strong>{{ line.name }}</strong>
                    <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                  </div>
                  @if (detailedMode() && line.children.length > 0) {
                    <ul class="bs-sublist">
                      @for (sub of line.children; track sub.id + '-' + sub.name) {
                        <li class="bs-subrow" (dblclick)="drillLine(sub)">
                          <span>{{ sub.name }}</span>
                          <span class="mc-amount">{{ sub.amount | mcIndianCurrency }}</span>
                        </li>
                      }
                    </ul>
                  }
                </li>
              }
            </ul>
            <div class="bs-col-total">
              <strong>Total Sources of Funds</strong>
              <strong class="mc-amount" data-testid="bs-total-liabilities">
                {{ r.totalLiabilities | mcIndianCurrency }}
              </strong>
            </div>

            <div class="vertical-section-title">II. Application of Funds (Assets)</div>
            <ul class="bs-list">
              @for (line of r.assetLines; track line.id + '-' + line.name) {
                <li
                  class="bs-row"
                  [class.bs-row-active]="selectedKey() === 'A:' + line.id"
                  (click)="selectedKey.set('A:' + line.id)"
                  (dblclick)="drillLine(line)"
                >
                  <div class="bs-row-main">
                    <strong>{{ line.name }}</strong>
                    <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                  </div>
                  @if (detailedMode() && line.children.length > 0) {
                    <ul class="bs-sublist">
                      @for (sub of line.children; track sub.id + '-' + sub.name) {
                        <li class="bs-subrow" (dblclick)="drillLine(sub)">
                          <span>{{ sub.name }}</span>
                          <span class="mc-amount">{{ sub.amount | mcIndianCurrency }}</span>
                        </li>
                      }
                    </ul>
                  }
                </li>
              }
            </ul>
            <div class="bs-col-total">
              <strong>Total Application of Funds</strong>
              <strong class="mc-amount" data-testid="bs-total-assets">
                {{ r.totalAssets | mcIndianCurrency }}
              </strong>
            </div>
          </section>
        }

        <footer class="mc-card bs-footer">
          <div class="nav-links">
            @if (r.isBalanced) {
              <span class="mc-badge mc-badge-jade" data-testid="bs-balanced-badge">
                Assets = Liabilities ({{ r.totalAssets | mcIndianCurrency }})
              </span>
            } @else {
              <span class="diff-warn">Difference: {{ r.differenceAmount | mcIndianCurrency }}</span>
            }
            @if (isNonZero(r.openingBalanceDifference)) {
              <span class="mc-badge mc-badge-slate">
                Opening Diff: {{ r.openingBalanceDifference | mcIndianCurrency }}
              </span>
            }
            <a routerLink="/app/reports/pnl" class="mc-btn">Profit &amp; Loss A/c</a>
            <a routerLink="/app/reports/schedule-iii" class="mc-btn">MCA Schedule III</a>
            <a routerLink="/app/reports/ratios" class="mc-btn">Ratio Analysis</a>
          </div>
          <span class="hint-text">Enter = Drill Down &bull; Ctrl+H = Horizontal / Vertical &bull; Ctrl+P = PDF</span>
        </footer>
      }

      @if (dateModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Balance Sheet As-Of Date"
          (keydown.escape)="dateModalOpen.set(false)"
        >
          <form class="mc-modal-dialog date-dialog" (ngSubmit)="applyDate()">
            <div class="mc-modal-header">
              <span>Balance Sheet As-Of Date (F2)</span>
              <button type="button" class="mc-btn" (click)="dateModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body">
              <label class="field-col">
                <span>As at Date</span>
                <input type="date" name="draftAsOf" class="mc-input" [(ngModel)]="draftAsOf" required />
              </label>
            </div>
            <div class="mc-modal-footer">
              <button type="submit" class="mc-btn mc-btn-primary">Apply (Enter)</button>
            </div>
          </form>
        </div>
      }
    </div>
  `,
  styles: `
    .bs-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .bs-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .bs-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.15rem;
      font-weight: 700;
    }

    .bs-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .bs-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .bs-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    .bs-banner {
      padding: 0.5rem 0.85rem;
      background: #ecfdf5;
      border: 1px solid #6ee7b7;
      color: #065f46;
      border-radius: 6px;
      font-size: 0.8rem;
      font-weight: 600;
    }

    .state-box {
      padding: 2rem;
      text-align: center;
      color: #475569;
      font-weight: 500;
    }

    .bs-card {
      overflow: hidden;
    }

    .bs-two-col {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }

    @media (max-width: 860px) {
      .bs-two-col {
        grid-template-columns: 1fr;
      }
    }

    .bs-col {
      display: flex;
      flex-direction: column;
      min-height: 240px;
    }

    .bs-col-left {
      border-right: 1px solid #cbd5e1;
    }

    .bs-col-head {
      padding: 0.55rem 0.95rem;
      background: #f1f5f9;
      border-bottom: 1px solid #cbd5e1;
      display: flex;
      justify-content: space-between;
      font-size: 0.74rem;
      font-weight: 700;
      color: #1e293b;
      text-transform: uppercase;
    }

    .amt-heads,
    .amt-pair {
      display: flex;
      align-items: center;
      gap: 1.25rem;
    }

    .muted-amt {
      color: #64748b;
      font-weight: 500;
    }

    .bs-list {
      list-style: none;
      margin: 0;
      padding: 0;
      flex: 1;
    }

    .bs-row {
      padding: 0.5rem 0.95rem;
      border-bottom: 1px solid #f1f5f9;
      cursor: pointer;
    }

    .bs-row-active {
      background: #e6f7f5;
      border-left: 3px solid #008d87;
    }

    .bs-row-main {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 0.86rem;
    }

    .bs-sublist {
      list-style: none;
      margin: 0.35rem 0 0;
      padding-left: 1rem;
      border-left: 2px solid #cbd5e1;
    }

    .bs-subrow {
      padding: 0.22rem 0;
      display: flex;
      justify-content: space-between;
      font-size: 0.78rem;
      color: #475569;
      font-weight: 500;
    }

    .bs-col-total {
      padding: 0.6rem 0.95rem;
      background: #f6faf9;
      border-top: 2px solid #142f42;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .vertical-section-title {
      padding: 0.55rem 0.95rem;
      background: #142f42;
      color: #ffffff;
      font-size: 0.8rem;
      font-weight: 700;
      letter-spacing: 0.03em;
    }

    .bs-footer {
      padding: 0.65rem 1rem;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .nav-links {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      flex-wrap: wrap;
    }

    .diff-warn {
      color: #b91c1c;
      font-weight: 700;
      font-size: 0.8rem;
    }

    .hint-text {
      font-size: 0.75rem;
      color: #475569;
      font-weight: 500;
    }

    .date-dialog {
      width: min(380px, 92vw);
    }

    .field-col {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      font-size: 0.8rem;
      font-weight: 600;
    }
  `,
})
export class BalanceSheetComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly asOfDate = signal<string>(this.companyContext.periodTo());
  readonly format = signal<BalanceSheetFormat>('HORIZONTAL');
  readonly includeOptional = signal<boolean>(false);
  readonly detailedMode = signal<boolean>(false);
  readonly autoColumnar = signal<boolean>(false);

  readonly loading = signal<boolean>(false);
  readonly report = signal<BalanceSheetReportDto | null>(null);
  readonly selectedKey = signal<string>('');
  readonly statusMessage = signal<string | null>(null);
  readonly dateModalOpen = signal<boolean>(false);

  draftAsOf = '';

  readonly navigableLines = computed<readonly { readonly key: string; readonly line: FinancialStatementLineDto }[]>(
    () => {
      const r = this.report();
      if (!r) return [];
      return [
        ...r.liabilityLines.map((line) => ({ key: 'L:' + line.id, line })),
        ...r.assetLines.map((line) => ({ key: 'A:' + line.id, line })),
      ];
    },
  );

  ngOnInit(): void {
    const disposers = [
      this.shortcutRegistry.bindHandler('voucher.changeMode', 'SCREEN', () => {
        this.toggleFormat();
      }),
      this.shortcutRegistry.bindHandler('report.toggleDetailed', 'SCREEN', () => {
        this.detailedMode.set(!this.detailedMode());
      }),
      this.shortcutRegistry.bindHandler('report.autoColumn', 'SCREEN', () => {
        this.autoColumnar.set(!this.autoColumnar());
      }),
      this.shortcutRegistry.bindHandler('report.basisOfValues', 'SCREEN', () => {
        this.toggleOptional();
      }),
      this.shortcutRegistry.bindHandler('context.changeDate', 'SCREEN', () => {
        this.openDateModal();
      }),
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        void this.exportPdf();
      }),
    ];
    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
    });
    void this.reload();
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.loading.set(true);
    try {
      const data = await this.reportsService.getBalanceSheet(companyId, {
        asOfDate: this.asOfDate(),
        format: this.format(),
        includeOptional: this.includeOptional(),
      });
      this.report.set(data);
      const first = this.navigableLines()[0];
      if (first) this.selectedKey.set(first.key);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  toggleFormat(): void {
    const next: BalanceSheetFormat = this.format() === 'HORIZONTAL' ? 'VERTICAL' : 'HORIZONTAL';
    this.format.set(next);
    void this.reload();
  }

  toggleOptional(): void {
    this.includeOptional.set(!this.includeOptional());
    void this.reload();
  }

  openDateModal(): void {
    this.draftAsOf = this.asOfDate();
    this.dateModalOpen.set(true);
  }

  applyDate(): void {
    if (this.draftAsOf) {
      this.asOfDate.set(this.draftAsOf);
      this.dateModalOpen.set(false);
      void this.reload();
    }
  }

  drillLine(line: FinancialStatementLineDto): void {
    const lower = line.name.toLowerCase();
    if (line.lineType === 'PROFIT_LOSS' || lower.includes('profit & loss')) {
      this.navStack.push({
        title: 'Profit & Loss A/c',
        route: '/app/reports/pnl',
      });
      return;
    }
    if (line.lineType === 'STOCK' || lower.includes('closing stock')) {
      this.navStack.push({
        title: 'Stock Summary',
        route: '/app/reports/stock-summary',
      });
      return;
    }
    if (line.lineType === 'LEDGER' || line.ledgerId) {
      this.navStack.push({
        title: `Ledger Vouchers: ${line.name}`,
        route: '/app/daybook',
        params: { ledgerId: line.ledgerId ?? line.id, ledgerName: line.name },
      });
      return;
    }
    this.navStack.push({
      title: `Trial Balance: ${line.name}`,
      route: '/app/reports/trial-balance',
      params: { groupId: line.groupId ?? line.id },
    });
  }

  async exportPdf(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    await this.reportsService.exportReportPdf(
      companyId,
      'balance-sheet',
      {
        asOfDate: this.asOfDate(),
        format: this.format(),
        includeOptional: this.includeOptional(),
        detailed: this.detailedMode(),
      },
      `BalanceSheet_${this.asOfDate()}.pdf`,
    );
    this.statusMessage.set('Balance Sheet PDF generated cleanly.');
  }

  isNonZero(wire: string): boolean {
    const dec = ExactDecimal.tryParse(wire, 6, 'HALF_UP');
    return dec !== null && !dec.isZero();
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (this.dateModalOpen() || isEditableTextElement(event.target)) return;
    if (this.navStack.hasOverlays() && !this.embeddedOverlay()) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;

    const items = this.navigableLines();
    if (items.length === 0) return;

    const idx = Math.max(0, items.findIndex((i) => i.key === this.selectedKey()));
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedKey.set(items[(idx + 1) % items.length].key);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedKey.set(items[(idx - 1 + items.length) % items.length].key);
    } else if (event.key === 'Enter') {
      const current = items[idx];
      if (current) {
        event.preventDefault();
        this.drillLine(current.line);
      }
    }
  }
}
