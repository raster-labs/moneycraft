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
import { FinancialStatementLineDto, ProfitAndLossReportDto } from './reports.models';
import { ReportsService } from './reports.service';

@Component({
  selector: 'mc-profit-and-loss',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe],
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
          <button type="button" class="mc-btn" (click)="openPeriodModal()">
            F2: Period
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="pnl-print-pdf-btn"
            (click)="exportPdf()"
          >
            Print PDF (Ctrl+P)
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
                &#x2212;{{ r.grossLoss | mcIndianCurrency }}
              } @else {
                {{ r.grossProfit | mcIndianCurrency }}
              }
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Net Profit / (Loss)</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="pnl-net-profit">
              @if (isPositive(r.netLoss)) {
                &#x2212;{{ r.netLoss | mcIndianCurrency }}
              } @else {
                {{ r.netProfit | mcIndianCurrency }}
              }
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Sales Accounts</span>
            <span class="mc-amount kpi-val" data-testid="pnl-sales-total">
              {{ r.salesTotal | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-card">
            <span class="kpi-label">Closing Stock</span>
            <span class="mc-amount kpi-val" data-testid="pnl-closing-stock">
              {{ r.closingStockValue | mcIndianCurrency }}
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
                      <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li class="child-row" (dblclick)="drillLine(ch)">
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ ch.amount | mcIndianCurrency }}</span>
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
                  {{ r.tradingTotal | mcIndianCurrency }}
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
                      <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li class="child-row" (dblclick)="drillLine(ch)">
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ ch.amount | mcIndianCurrency }}</span>
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
                  {{ r.tradingTotal | mcIndianCurrency }}
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
                      <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li class="child-row" (dblclick)="drillLine(ch)">
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ ch.amount | mcIndianCurrency }}</span>
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
                  {{ r.pnlTotal | mcIndianCurrency }}
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
                      <span class="mc-amount">{{ line.amount | mcIndianCurrency }}</span>
                    </div>
                    @if (detailedMode() && line.children.length > 0) {
                      <ul class="child-list">
                        @for (ch of line.children; track ch.id + '-' + ch.name) {
                          <li class="child-row" (dblclick)="drillLine(ch)">
                            <span>{{ ch.name }}</span>
                            <span class="mc-amount">{{ ch.amount | mcIndianCurrency }}</span>
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
                  {{ r.pnlTotal | mcIndianCurrency }}
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
              <button type="submit" class="mc-btn mc-btn-primary">Apply (Enter)</button>
            </div>
          </form>
        </div>
      }
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
      padding: 0.2rem 0;
      display: flex;
      justify-content: space-between;
      font-size: 0.78rem;
      color: #475569;
      font-weight: 500;
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

  readonly loading = signal<boolean>(false);
  readonly report = signal<ProfitAndLossReportDto | null>(null);
  readonly selectedKey = signal<string>('');
  readonly statusMessage = signal<string | null>(null);
  readonly periodModalOpen = signal<boolean>(false);

  draftFrom = '';
  draftTo = '';

  readonly tradingDrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.tradingDebitLines.length > 0) return r.tradingDebitLines;
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
    return synthesized;
  });

  readonly tradingCrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.tradingCreditLines.length > 0) return r.tradingCreditLines;
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
    return synthesized;
  });

  readonly pnlDrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.pnlDebitLines.length > 0) return r.pnlDebitLines;
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
    return synthesized;
  });

  readonly pnlCrRows = computed<readonly FinancialStatementLineDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    if (r.pnlCreditLines.length > 0) return r.pnlCreditLines;
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
    return synthesized;
  });

  readonly navigableLines = computed<readonly { readonly key: string; readonly line: FinancialStatementLineDto }[]>(
    () => [
      ...this.tradingDrRows().map((line) => ({ key: 'TDR:' + line.id, line })),
      ...this.tradingCrRows().map((line) => ({ key: 'TCR:' + line.id, line })),
      ...this.pnlDrRows().map((line) => ({ key: 'PDR:' + line.id, line })),
      ...this.pnlCrRows().map((line) => ({ key: 'PCR:' + line.id, line })),
    ],
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
