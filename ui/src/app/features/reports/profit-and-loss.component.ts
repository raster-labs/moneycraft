import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { ReportOutputModalComponent } from '../../shared/components/report-output-modal/report-output-modal.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { FinancialStatementLineDto, ProfitAndLossReportDto } from './reports.models';
import { ReportsService } from './reports.service';

@Component({
  selector: 'mc-profit-and-loss',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    IndianCurrencyPipe,
    SemanticIconComponent,
    ReportOutputModalComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pnl-page">
      <header class="mc-card pnl-header">
        <div>
          <span class="pnl-pill">Financial Statements &bull; Trading &amp; Profit &amp; Loss A/c</span>
          <h1>Profit &amp; Loss A/c</h1>
          <p class="pnl-sub">
            For the period <span class="mc-code">{{ fromDate() }}</span> to
            <span class="mc-code">{{ toDate() }}</span> &bull;
            {{ detailedMode() ? 'Detailed Breakdown (Alt+F1)' : 'Condensed Groups (Alt+F1)' }}
          </p>
        </div>

        <div class="pnl-actions">
          <button
            type="button"
            class="mc-btn"
            data-testid="pnl-toggle-detailed-btn"
            (click)="detailedMode.set(!detailedMode())"
          >
            Alt+F1: {{ detailedMode() ? 'Condensed' : 'Detailed' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            (click)="toggleOptional()"
          >
            Ctrl+B: {{ includeOptional() ? 'Postings Only' : 'Incl. Optional' }}
          </button>
          <select
            class="mc-input"
            aria-label="Scale Factor"
            data-testid="pnl-scale-factor-select"
            [ngModel]="scaleFactor()"
            (ngModelChange)="setScaleFactor($event)"
          >
            <option value="DEFAULT">Scale: Default</option>
            <option value="HUNDREDS">In Hundreds</option>
            <option value="THOUSANDS">In Thousands</option>
            <option value="LAKHS">In Lakhs</option>
            <option value="MILLIONS">In Millions</option>
            <option value="CRORES">In Crores</option>
          </select>
          <button type="button" class="mc-btn mc-btn-secondary" (click)="openPeriodModal()">
            <mc-icon name="calendar" [size]="16" />
            <span>F2: Period</span>
          </button>
          @if (removedKeys().length > 0) {
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="pnl-restore-line-btn"
              (click)="restoreLastRemovedLine()"
            >
              Restore Hidden ({{ removedKeys().length }}) &bull; Alt+U
            </button>
          }
          <button
            type="button"
            class="mc-btn mc-btn-secondary"
            data-testid="pnl-output-btn"
            (click)="outputModalOpen.set(true)"
          >
            <mc-icon name="export" [size]="16" />
            <span>Output</span>
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="pnl-print-pdf-btn"
            (click)="exportPdf()"
          >
            <mc-icon name="print" [size]="16" />
            <span>Print PDF (Ctrl+P)</span>
          </button>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="pnl-banner" data-testid="pnl-status-banner" role="status">{{ msg }}</div>
      }

      @if (loading()) {
        <div class="mc-card state-box">Synthesizing Trading &amp; Profit &amp; Loss A/c...</div>
      } @else if (report(); as r) {
        <!-- KPI Summary Strip -->
        <section class="kpi-strip" aria-label="Profit and Loss Highlights">
          <article class="mc-card kpi-card">
            <span class="kpi-label">Gross Profit / (Loss)</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="pnl-gross-profit">
              @if (isPositive(r.grossLoss)) {
                &#x2212;{{ scaleAmount(r.grossLoss) | mcIndianCurrency }}
              } @else {
                {{ scaleAmount(r.grossProfit) | mcIndianCurrency }}
              }
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Net Profit / (Loss)</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="pnl-net-profit">
              @if (isPositive(r.netLoss)) {
                &#x2212;{{ scaleAmount(r.netLoss) | mcIndianCurrency }}
              } @else {
                {{ scaleAmount(r.netProfit) | mcIndianCurrency }}
              }
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Sales Accounts</span>
            <span class="mc-amount kpi-val" data-testid="pnl-sales-total">
              {{ scaleAmount(r.salesTotal) | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Closing Stock</span>
            <span class="mc-amount kpi-val" data-testid="pnl-closing-stock">
              {{ scaleAmount(r.closingStockValue) | mcIndianCurrency }}
            </span>
          </article>
        </section>

        <!-- Tier 1: Trading Account -->
        <section class="mc-card tier-card" data-testid="pnl-trading-account">
          <header class="tier-head">
            <h2>Trading Account</h2>
            <span>Gross Trading Margin</span>
          </header>
          <div class="two-pane-grid">
            <!-- Left: Trading Debit (Opening Stock, Purchases, Direct Expenses, Gross Profit c/o) -->
            <div class="pane pane-left">
              <div class="pane-col-head">
                <span>Particulars (Dr)</span>
                <span>Amount</span>
              </div>
              <ul class="stmt-list">
                @for (line of tradingDrRows(); track line.id + '-' + line.name) {
                  <li
                    class="stmt-row"
                    [class.stmt-row-active]="selectedKey() === 'TDR:' + line.id"
                    [class.stmt-row-profit]="line.lineType === 'PROFIT_LOSS'"
                    (click)="selectLine('TDR:' + line.id)"
                    (dblclick)="drillLine(line)"
                  >
                    <div class="stmt-main">
                      <span>{{ line.name }}</span>
                      <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li
                            class="child-row"
                            [class.child-row-active]="selectedKey() === 'TDR:' + line.id + ':' + ch.id"
                            (click)="selectLine('TDR:' + line.id + ':' + ch.id); $event.stopPropagation()"
                            (dblclick)="drillLine(ch); $event.stopPropagation()"
                          >
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ scaleAmount(ch.amount) | mcIndianCurrency }}</span>
                          </li>
                        }
                      </ul>
                    }
                  </li>
                }
              </ul>
              <div class="pane-total">
                <strong>Total</strong>
                <strong class="mc-amount" data-testid="pnl-trading-total-dr">
                  {{ scaleAmount(r.tradingTotal) | mcIndianCurrency }}
                </strong>
              </div>
            </div>

            <!-- Right: Trading Credit (Sales, Direct Incomes, Closing Stock, Gross Loss c/o) -->
            <div class="pane">
              <div class="pane-col-head">
                <span>Particulars (Cr)</span>
                <span>Amount</span>
              </div>
              <ul class="stmt-list">
                @for (line of tradingCrRows(); track line.id + '-' + line.name) {
                  <li
                    class="stmt-row"
                    [class.stmt-row-active]="selectedKey() === 'TCR:' + line.id"
                    [class.stmt-row-profit]="line.lineType === 'PROFIT_LOSS'"
                    (click)="selectLine('TCR:' + line.id)"
                    (dblclick)="drillLine(line)"
                  >
                    <div class="stmt-main">
                      <span>{{ line.name }}</span>
                      <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li
                            class="child-row"
                            [class.child-row-active]="selectedKey() === 'TCR:' + line.id + ':' + ch.id"
                            (click)="selectLine('TCR:' + line.id + ':' + ch.id); $event.stopPropagation()"
                            (dblclick)="drillLine(ch); $event.stopPropagation()"
                          >
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ scaleAmount(ch.amount) | mcIndianCurrency }}</span>
                          </li>
                        }
                      </ul>
                    }
                  </li>
                }
              </ul>
              <div class="pane-total">
                <strong>Total</strong>
                <strong class="mc-amount" data-testid="pnl-trading-total-cr">
                  {{ scaleAmount(r.tradingTotal) | mcIndianCurrency }}
                </strong>
              </div>
            </div>
          </div>
        </section>

        <!-- Tier 2: Profit & Loss Account -->
        <section class="mc-card tier-card" data-testid="pnl-account-section">
          <header class="tier-head">
            <h2>Profit &amp; Loss Account</h2>
            <span>Indirect Incomes, Indirect Expenses &amp; Net Profit</span>
          </header>
          <div class="two-pane-grid">
            <div class="pane pane-left">
              <div class="pane-col-head">
                <span>Particulars (Dr)</span>
                <span>Amount</span>
              </div>
              <ul class="stmt-list">
                @for (line of pnlDrRows(); track line.id + '-' + line.name) {
                  <li
                    class="stmt-row"
                    [class.stmt-row-active]="selectedKey() === 'PDR:' + line.id"
                    [class.stmt-row-profit]="line.lineType === 'PROFIT_LOSS'"
                    (click)="selectLine('PDR:' + line.id)"
                    (dblclick)="drillLine(line)"
                  >
                    <div class="stmt-main">
                      <span>{{ line.name }}</span>
                      <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li
                            class="child-row"
                            [class.child-row-active]="selectedKey() === 'PDR:' + line.id + ':' + ch.id"
                            (click)="selectLine('PDR:' + line.id + ':' + ch.id); $event.stopPropagation()"
                            (dblclick)="drillLine(ch); $event.stopPropagation()"
                          >
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ scaleAmount(ch.amount) | mcIndianCurrency }}</span>
                          </li>
                        }
                      </ul>
                    }
                  </li>
                }
              </ul>
              <div class="pane-total">
                <strong>Total</strong>
                <strong class="mc-amount" data-testid="pnl-total-dr">
                  {{ scaleAmount(r.pnlTotal) | mcIndianCurrency }}
                </strong>
              </div>
            </div>

            <div class="pane">
              <div class="pane-col-head">
                <span>Particulars (Cr)</span>
                <span>Amount</span>
              </div>
              <ul class="stmt-list">
                @for (line of pnlCrRows(); track line.id + '-' + line.name) {
                  <li
                    class="stmt-row"
                    [class.stmt-row-active]="selectedKey() === 'PCR:' + line.id"
                    [class.stmt-row-profit]="line.lineType === 'PROFIT_LOSS'"
                    (click)="selectLine('PCR:' + line.id)"
                    (dblclick)="drillLine(line)"
                  >
                    <div class="stmt-main">
                      <span>{{ line.name }}</span>
                      <span class="mc-amount">{{ scaleAmount(line.amount) | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li
                            class="child-row"
                            [class.child-row-active]="selectedKey() === 'PCR:' + line.id + ':' + ch.id"
                            (click)="selectLine('PCR:' + line.id + ':' + ch.id); $event.stopPropagation()"
                            (dblclick)="drillLine(ch); $event.stopPropagation()"
                          >
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ scaleAmount(ch.amount) | mcIndianCurrency }}</span>
                          </li>
                        }
                      </ul>
                    }
                  </li>
                }
              </ul>
              <div class="pane-total">
                <strong>Total</strong>
                <strong class="mc-amount" data-testid="pnl-total-cr">
                  {{ scaleAmount(r.pnlTotal) | mcIndianCurrency }}
                </strong>
              </div>
            </div>
          </div>

          <footer class="pnl-footer">
            <div class="nav-links">
              <a routerLink="/app/reports/balance-sheet" class="mc-btn">Balance Sheet</a>
              <a routerLink="/app/reports/trial-balance" class="mc-btn">Trial Balance</a>
              <a routerLink="/app/reports/stock-summary" class="mc-btn">Stock Summary</a>
            </div>
            <span class="hint-text">Arrow Up/Down + Enter = Drill Down onto Go To Stack &bull; Ctrl+P = PDF</span>
          </footer>
        </section>
      }

      @if (periodModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Profit and Loss Period"
          (keydown.escape)="periodModalOpen.set(false)"
        >
          <form class="mc-modal-dialog period-dialog" (ngSubmit)="applyPeriod()">
            <div class="mc-modal-header">
              <span>Profit &amp; Loss Period (F2)</span>
              <button type="button" class="mc-btn" (click)="periodModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body period-fields">
              <label class="field-col">
                <span>From</span>
                <input type="date" name="draftFrom" class="mc-input" [(ngModel)]="draftFrom" required />
              </label>
              <label class="field-col">
                <span>To</span>
                <input type="date" name="draftTo" class="mc-input" [(ngModel)]="draftTo" required />
              </label>
            </div>
            <div class="mc-modal-footer">
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                (click)="periodModalOpen.set(false)"
              >
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
                @if (dateDialogHint('editor.close', 'C/c'); as cHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ cHint }}]</span>
                }
              </button>
              <button type="submit" class="mc-btn mc-btn-primary">
                <mc-icon name="calendar" [size]="16" />
                <span>Apply</span>
                @if (dateDialogHint('context.applyDate', 'A/a'); as aHint) {
                  <span class="mc-action-key-badge" aria-hidden="true">[{{ aHint }}]</span>
                }
              </button>
            </div>
          </form>
        </div>
      }

      <mc-report-output-modal
        [open]="outputModalOpen()"
        reportTitle="Profit & Loss A/c"
        [boundsLabel]="fromDate() + ' to ' + toDate()"
        (printRequested)="exportPdf()"
        (exportRequested)="exportPdf()"
        (closed)="outputModalOpen.set(false)"
      />
    </div>
  `,
  styles: `
    .pnl-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .pnl-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .pnl-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.15rem;
      font-weight: 700;
    }

    .pnl-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .pnl-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .pnl-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    .pnl-banner {
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

    .kpi-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(185px, 1fr));
      gap: 0.65rem;
    }

    .kpi-card {
      padding: 0.75rem 0.95rem;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .kpi-label {
      font-size: 0.72rem;
      font-weight: 700;
      color: #64748b;
      text-transform: uppercase;
    }

    .kpi-val {
      font-size: 1.05rem;
      font-weight: 700;
      color: #0f172a;
    }

    .kpi-teal {
      color: #006d68;
    }

    .tier-card {
      overflow: hidden;
    }

    .tier-head {
      padding: 0.6rem 1rem;
      background: #f6faf9;
      border-bottom: 1px solid #cbd5e1;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .tier-head h2 {
      margin: 0;
      font-size: 0.92rem;
      font-weight: 700;
      color: #142f42;
    }

    .tier-head span {
      font-size: 0.74rem;
      color: #64748b;
      font-weight: 500;
    }

    .two-pane-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
    }

    @media (max-width: 860px) {
      .two-pane-grid {
        grid-template-columns: 1fr;
      }
    }

    .pane {
      display: flex;
      flex-direction: column;
      min-height: 150px;
    }

    .pane-left {
      border-right: 1px solid #cbd5e1;
    }

    .pane-col-head {
      padding: 0.45rem 0.9rem;
      background: #f1f5f9;
      border-bottom: 1px solid #e2e8f0;
      display: flex;
      justify-content: space-between;
      font-size: 0.73rem;
      font-weight: 700;
      color: #334155;
      text-transform: uppercase;
    }

    .stmt-list {
      list-style: none;
      margin: 0;
      padding: 0;
      flex: 1;
    }

    .stmt-row {
      padding: 0.45rem 0.9rem;
      border-bottom: 1px solid #f1f5f9;
      cursor: pointer;
    }

    .stmt-row-active {
      background: #e6f7f5;
      border-left: 3px solid #008d87;
    }

    .stmt-row-profit {
      background: #f0fdf4;
      font-weight: 700;
      color: #065f46;
    }

    .stmt-main {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-weight: 600;
      font-size: 0.85rem;
    }

    .child-list {
      list-style: none;
      margin: 0.3rem 0 0;
      padding-left: 1rem;
      border-left: 2px solid #cbd5e1;
    }

    .child-row {
      padding: 0.2rem 0.35rem;
      display: flex;
      justify-content: space-between;
      font-size: 0.78rem;
      color: #475569;
      font-weight: 500;
      border-radius: 3px;
    }

    .child-row-active {
      background: #006d68;
      color: #ffffff;
      font-weight: 600;
    }

    .pane-total {
      padding: 0.55rem 0.9rem;
      background: #f6faf9;
      border-top: 2px solid #142f42;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .pnl-footer {
      padding: 0.65rem 1rem;
      border-top: 1px solid #e2e8f0;
      background: #f8fafc;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .nav-links {
      display: flex;
      gap: 0.4rem;
    }

    .hint-text {
      font-size: 0.75rem;
      color: #475569;
      font-weight: 500;
    }

    .period-dialog {
      width: min(400px, 92vw);
    }

    .period-fields {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
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
export class ProfitAndLossComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly fromDate = signal<string>(this.companyContext.periodFrom());
  readonly toDate = signal<string>(this.companyContext.periodTo());
  readonly includeOptional = signal<boolean>(false);
  readonly detailedMode = signal<boolean>(false);
  readonly scaleFactor = signal<
    'DEFAULT' | 'HUNDREDS' | 'THOUSANDS' | 'LAKHS' | 'MILLIONS' | 'CRORES'
  >('DEFAULT');

  readonly loading = signal<boolean>(false);
  readonly report = signal<ProfitAndLossReportDto | null>(null);
  readonly selectedKey = signal<string>('');
  readonly statusMessage = signal<string | null>(null);
  readonly periodModalOpen = signal<boolean>(false);
  readonly outputModalOpen = signal<boolean>(false);
  readonly removedKeys = signal<readonly string[]>([]);

  draftFrom = '';
  draftTo = '';
  private periodModalHandle: ActiveModalContextHandle | null = null;

  dateDialogHint(commandId: string, pairSpec: 'A/a' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'context.dateDialog',
      commandId,
      asciiPair(pairSpec),
    );
  }

  constructor() {
    effect(() => {
      const open = this.periodModalOpen();
      if (this.periodModalHandle) {
        this.periodModalHandle.close();
        this.periodModalHandle = null;
      }
      if (open) {
        this.periodModalHandle = this.shortcutRegistry.openModalContext(
          [
            {
              contextId: 'context.dateDialog',
              locale: 'en-GB',
              commandId: 'context.applyDate',
              label: 'Apply',
              letterPair: asciiPair('A/a'),
              permissionRef: 'report.read',
              effect: 'Apply selected Profit & Loss period.',
              normalActivation: () => this.applyPeriod(),
              iconSemanticId: 'mc.icon.calendar',
              stateTokenRole: 'action.primary',
            },
            {
              contextId: 'context.dateDialog',
              locale: 'en-GB',
              commandId: 'editor.close',
              label: 'Close',
              letterPair: asciiPair('C/c'),
              permissionRef: 'authenticated',
              effect: 'Close Profit & Loss period dialog.',
              normalActivation: () => this.periodModalOpen.set(false),
              iconSemanticId: 'mc.icon.close',
              stateTokenRole: 'action.secondary',
            },
          ],
          'en-GB',
        );
      }
    });
  }

  readonly tradingDrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.tradingDebitLines.length > 0) {
      return r.tradingDebitLines.filter((l) => !this.removedKeys().includes('TDR:' + l.id));
    }
    const synthesized: FinancialStatementLineDto[] = [
      { id: 'opening-stock', lineType: 'STOCK', name: 'Opening Stock', amount: r.openingStockValue, children: [] },
      { id: 'purchase-accounts', lineType: 'GROUP', name: 'Purchase Accounts', amount: r.purchaseTotal, children: [] },
    ];
    if (this.isPositive(r.directExpensesTotal)) {
      synthesized.push({
        id: 'direct-expenses',
        lineType: 'GROUP',
        name: 'Direct Expenses',
        amount: r.directExpensesTotal,
        children: [],
      });
    }
    if (this.isPositive(r.grossProfit)) {
      synthesized.push({
        id: 'gross-profit-co',
        lineType: 'PROFIT_LOSS',
        name: 'Gross Profit c/o',
        amount: r.grossProfit,
        children: [],
      });
    }
    return synthesized.filter((l) => !this.removedKeys().includes('TDR:' + l.id));
  });

  readonly tradingCrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    const hidden = this.removedKeys();
    if (r.tradingCreditLines.length > 0) {
      return r.tradingCreditLines.filter((l) => !hidden.includes('TCR:' + l.id));
    }
    const synthesized: FinancialStatementLineDto[] = [
      { id: 'sales-accounts', lineType: 'GROUP', name: 'Sales Accounts', amount: r.salesTotal, children: [] },
    ];
    if (this.isPositive(r.directIncomesTotal)) {
      synthesized.push({
        id: 'direct-incomes',
        lineType: 'GROUP',
        name: 'Direct Incomes',
        amount: r.directIncomesTotal,
        children: [],
      });
    }
    synthesized.push({
      id: 'closing-stock',
      lineType: 'STOCK',
      name: 'Closing Stock',
      amount: r.closingStockValue,
      children: [],
    });
    if (this.isPositive(r.grossLoss)) {
      synthesized.push({
        id: 'gross-loss-co',
        lineType: 'PROFIT_LOSS',
        name: 'Gross Loss c/o',
        amount: r.grossLoss,
        children: [],
      });
    }
    return synthesized.filter((l) => !this.removedKeys().includes('TCR:' + l.id));
  });

  readonly pnlDrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.pnlDebitLines.length > 0) {
      return r.pnlDebitLines.filter((l) => !this.removedKeys().includes('PDR:' + l.id));
    }
    const synthesized: FinancialStatementLineDto[] = [];
    if (this.isPositive(r.grossLoss)) {
      synthesized.push({
        id: 'gross-loss-bf',
        lineType: 'PROFIT_LOSS',
        name: 'Gross Loss b/f',
        amount: r.grossLoss,
        children: [],
      });
    }
    synthesized.push({
      id: 'indirect-expenses',
      lineType: 'GROUP',
      name: 'Indirect Expenses',
      amount: r.indirectExpensesTotal,
      children: [],
    });
    if (this.isPositive(r.netProfit)) {
      synthesized.push({
        id: 'net-profit',
        lineType: 'PROFIT_LOSS',
        name: 'Net Profit',
        amount: r.netProfit,
        children: [],
      });
    }
    return synthesized.filter((l) => !this.removedKeys().includes('PDR:' + l.id));
  });

  readonly pnlCrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.pnlCreditLines.length > 0) {
      return r.pnlCreditLines.filter((l) => !this.removedKeys().includes('PCR:' + l.id));
    }
    const synthesized: FinancialStatementLineDto[] = [];
    if (this.isPositive(r.grossProfit)) {
      synthesized.push({
        id: 'gross-profit-bf',
        lineType: 'PROFIT_LOSS',
        name: 'Gross Profit b/f',
        amount: r.grossProfit,
        children: [],
      });
    }
    if (this.isPositive(r.indirectIncomesTotal)) {
      synthesized.push({
        id: 'indirect-incomes',
        lineType: 'GROUP',
        name: 'Indirect Incomes',
        amount: r.indirectIncomesTotal,
        children: [],
      });
    }
    if (this.isPositive(r.netLoss)) {
      synthesized.push({
        id: 'net-loss',
        lineType: 'PROFIT_LOSS',
        name: 'Net Loss',
        amount: r.netLoss,
        children: [],
      });
    }
    return synthesized.filter((l) => !this.removedKeys().includes('PCR:' + l.id));
  });

  readonly navigableLines = computed<readonly { readonly key: string; readonly line: FinancialStatementLineDto }[]>(
    () => {
      const detailed = this.detailedMode();
      const out: { readonly key: string; readonly line: FinancialStatementLineDto }[] = [];
      const appendRows = (prefix: string, rows: readonly FinancialStatementLineDto[]): void => {
        for (const line of rows) {
          out.push({ key: `${prefix}:${line.id}`, line });
          if (detailed && line.children?.length) {
            for (const ch of line.children) {
              out.push({ key: `${prefix}:${line.id}:${ch.id}`, line: ch });
            }
          }
        }
      };
      appendRows('TDR', this.tradingDrRows());
      appendRows('TCR', this.tradingCrRows());
      appendRows('PDR', this.pnlDrRows());
      appendRows('PCR', this.pnlCrRows());
      return out;
    },
  );

  ngOnInit(): void {
    const disposers = [
      this.shortcutRegistry.bindHandler('report.toggleDetailed', 'SCREEN', () => {
        this.detailedMode.set(!this.detailedMode());
      }),
      this.shortcutRegistry.bindHandler('report.basisOfValues', 'SCREEN', () => {
        this.toggleOptional();
      }),
      this.shortcutRegistry.bindHandler('context.changeDate', 'SCREEN', () => {
        this.openPeriodModal();
      }),
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        void this.exportPdf();
      }),
      this.shortcutRegistry.bindHandler('register.removeLine', 'SCREEN', () => {
        this.removeSelectedLine();
      }),
      this.shortcutRegistry.bindHandler('register.restoreLine', 'SCREEN', () => {
        this.restoreLastRemovedLine();
      }),
      this.shortcutRegistry.bindHandler('register.restoreAllLines', 'SCREEN', () => {
        this.removedKeys.set([]);
      }),
      this.shortcutRegistry.bindHandler('report.explodeRow', 'SCREEN', () => {
        this.detailedMode.set(!this.detailedMode());
      }),
      this.shortcutRegistry.bindHandler('report.addColumn', 'SCREEN', () => {
        this.detailedMode.set(!this.detailedMode());
      }),
      this.shortcutRegistry.bindHandler(
        'nav.escape',
        'MODAL',
        () => {
          if (this.outputModalOpen()) {
            this.outputModalOpen.set(false);
            return true;
          }
          if (this.periodModalOpen()) {
            this.periodModalOpen.set(false);
            return true;
          }
          return false;
        },
        () => this.periodModalOpen() || this.outputModalOpen(),
      ),
    ];
    this.destroyRef.onDestroy(() => {
      for (const d of disposers) d();
      if (this.periodModalHandle) {
        this.periodModalHandle.close();
        this.periodModalHandle = null;
      }
    });
    void this.reload();
  }

  removeSelectedLine(): void {
    const items = this.navigableLines();
    if (items.length === 0) return;
    const currentKey = this.selectedKey() || items[0].key;
    if (!this.removedKeys().includes(currentKey)) {
      this.removedKeys.set([...this.removedKeys(), currentKey]);
      const next = this.navigableLines()[0];
      this.selectedKey.set(next ? next.key : '');
    }
  }

  restoreLastRemovedLine(): void {
    const stack = this.removedKeys();
    if (stack.length === 0) return;
    this.removedKeys.set(stack.slice(0, -1));
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.loading.set(true);
    try {
      const data = await this.reportsService.getProfitAndLoss(companyId, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
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

  toggleOptional(): void {
    this.includeOptional.set(!this.includeOptional());
    void this.reload();
  }

  setScaleFactor(
    factor: 'DEFAULT' | 'HUNDREDS' | 'THOUSANDS' | 'LAKHS' | 'MILLIONS' | 'CRORES',
  ): void {
    this.scaleFactor.set(factor);
  }

  scaleAmount(wire: string): string {
    const factor = this.scaleFactor();
    if (factor === 'DEFAULT') return wire;
    const scale = this.companyContext.currencyScale();
    const dec = ExactDecimal.tryParse(wire, scale, 'HALF_UP');
    if (!dec) return wire;
    const divisorInt =
      factor === 'HUNDREDS'
        ? 100
        : factor === 'THOUSANDS'
          ? 1000
          : factor === 'LAKHS'
            ? 100000
            : factor === 'MILLIONS'
              ? 1000000
              : 10000000;
    return dec.divHalfUp(ExactDecimal.fromInt(divisorInt, 0), scale).toWireString(scale);
  }

  openPeriodModal(): void {
    this.draftFrom = this.fromDate();
    this.draftTo = this.toDate();
    this.periodModalOpen.set(true);
  }

  applyPeriod(): void {
    if (this.draftFrom && this.draftTo) {
      this.fromDate.set(this.draftFrom);
      this.toDate.set(this.draftTo);
      this.periodModalOpen.set(false);
      void this.reload();
    }
  }

  selectLine(key: string): void {
    this.selectedKey.set(key);
  }

  drillLine(line: FinancialStatementLineDto): void {
    if (line.lineType === 'STOCK' || line.name.toLowerCase().includes('stock')) {
      this.navStack.push({
        title: `Stock Summary: ${line.name}`,
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
      'profit-and-loss',
      {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        includeOptional: this.includeOptional(),
        detailed: this.detailedMode(),
      },
      `ProfitAndLoss_${this.fromDate()}_${this.toDate()}.pdf`,
    );
    this.statusMessage.set('Profit & Loss A/c PDF generated cleanly.');
  }

  isPositive(wire: string): boolean {
    const dec = ExactDecimal.tryParse(wire, 6, 'HALF_UP');
    return dec !== null && dec.isPositive();
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (this.periodModalOpen() || isEditableTextElement(event.target)) return;
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
