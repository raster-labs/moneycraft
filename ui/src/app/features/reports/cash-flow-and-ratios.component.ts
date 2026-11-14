import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CompanyContextStore } from '../../state/company-context.store';
import { CashFlowReportDto, RatioAnalysisReportDto } from './reports.models';
import { ReportsService } from './reports.service';

export type CashFlowRatioTab = 'CASH_FLOW' | 'RATIOS';

@Component({
  selector: 'mc-cash-flow-and-ratios',
  standalone: true,
  imports: [FormsModule, RouterLink, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cfr-page">
      <header class="mc-card cfr-header">
        <div>
          <span class="cfr-pill">Liquidity, Solvency &amp; Profitability Analytics</span>
          <h1>
            {{
              activeTab() === 'CASH_FLOW'
                ? 'Cash Flow Statement (Operating, Investing & Financing)'
                : 'Ratio Analysis (Principal Groups & Principal Ratios)'
            }}
          </h1>
          <p class="cfr-sub">
            Period <span class="mc-code">{{ fromDate() }}</span> to
            <span class="mc-code">{{ toDate() }}</span>
          </p>
        </div>

        <div class="cfr-actions">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'CASH_FLOW'"
            data-testid="tab-cash-flow"
            (click)="switchTab('CASH_FLOW')"
          >
            Cash Flow
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'RATIOS'"
            data-testid="tab-ratios"
            (click)="switchTab('RATIOS')"
          >
            Ratio Analysis
          </button>
          <input
            type="date"
            class="mc-input"
            aria-label="From Date"
            [ngModel]="fromDate()"
            (ngModelChange)="fromDate.set($event)"
          />
          <input
            type="date"
            class="mc-input"
            aria-label="To Date"
            [ngModel]="toDate()"
            (ngModelChange)="toDate.set($event)"
          />
          <button type="button" class="mc-btn" (click)="reload()">Refresh</button>
        </div>
      </header>

      @if (loading()) {
        <div class="mc-card state-box">Computing Cash Flow &amp; Financial Ratios...</div>
      } @else if (activeTab() === 'CASH_FLOW' && cashFlow(); as cf) {
        <section class="kpi-grid" data-testid="cash-flow-kpis">
          <article class="mc-card kpi-tile">
            <span class="kpi-lbl">Opening Cash &amp; Bank</span>
            <span class="mc-amount kpi-val">{{ cf.openingCashAndBank | mcIndianCurrency }}</span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-lbl">Net Operating Flow</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="cf-net-operating">
              {{ cf.netOperatingCashFlow | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-lbl">Investing / Financing</span>
            <span class="mc-amount kpi-val">
              {{ cf.investingCashFlow | mcIndianCurrency }} /
              {{ cf.financingCashFlow | mcIndianCurrency }}
            </span>
          </article>
          <article class="mc-card kpi-tile">
            <span class="kpi-lbl">Closing Cash &amp; Bank</span>
            <span class="mc-amount kpi-val kpi-teal" data-testid="cf-closing-cash">
              {{ cf.closingCashAndBank | mcIndianCurrency }}
            </span>
          </article>
        </section>

        <div class="two-pane">
          <section class="mc-card pane-card">
            <header class="pane-head">
              <h2>Activity Classification (Operating / Investing / Financing)</h2>
            </header>
            <table class="mc-table">
              <thead>
                <tr>
                  <th>Activity</th>
                  <th>Particulars</th>
                  <th class="num-col">Inflow</th>
                  <th class="num-col">Outflow</th>
                  <th class="num-col">Net Flow</th>
                </tr>
              </thead>
              <tbody>
                @for (act of cf.activities; track act.category + '-' + act.particulars) {
                  <tr>
                    <td><span class="mc-badge mc-badge-slate">{{ act.category }}</span></td>
                    <td>{{ act.particulars }}</td>
                    <td class="mc-amount num-col">{{ act.inflow | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ act.outflow | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col"><strong>{{ act.netFlow | mcIndianCurrency }}</strong></td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="5" class="empty-cell">
                      Net Movement: {{ cf.netCashMovement | mcIndianCurrency }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </section>

          <section class="mc-card pane-card">
            <header class="pane-head">
              <h2>Monthly Cash Inflow / Outflow Summary</h2>
            </header>
            <table class="mc-table" data-testid="cf-monthly-table">
              <thead>
                <tr>
                  <th>Month</th>
                  <th class="num-col">Inflow</th>
                  <th class="num-col">Outflow</th>
                  <th class="num-col">Net Flow</th>
                  <th class="num-col">Closing</th>
                </tr>
              </thead>
              <tbody>
                @for (m of cf.monthlySummary; track m.monthLabel + m.fromDate) {
                  <tr>
                    <td><strong>{{ m.monthLabel }}</strong></td>
                    <td class="mc-amount num-col">{{ m.inflow | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ m.outflow | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ m.netFlow | mcIndianCurrency }}</td>
                    <td class="mc-amount num-col">{{ m.closingBalance | mcIndianCurrency }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="5" class="empty-cell">No monthly cash movements in period.</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>
        </div>
      } @else if (activeTab() === 'RATIOS' && ratios(); as ra) {
        <!-- Authentic Tally Two-Pane Ratio Analysis: Principal Groups | Principal Ratios -->
        <div class="two-pane" data-testid="ratios-two-pane">
          <section class="mc-card pane-card">
            <header class="pane-head">
              <h2>Principal Groups</h2>
            </header>
            <table class="mc-table">
              <tbody>
                <tr>
                  <td><strong>Working Capital</strong> (Current Assets &#x2212; Current Liabilities)</td>
                  <td class="mc-amount num-col" data-testid="ratio-working-capital">
                    <strong>{{ ra.workingCapital | mcIndianCurrency }}</strong>
                  </td>
                </tr>
                <tr>
                  <td>Cash-in-Hand</td>
                  <td class="mc-amount num-col">{{ ra.cashInHand | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td>Bank Accounts</td>
                  <td class="mc-amount num-col">{{ ra.bankAccounts | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td>Sundry Debtors (Bills Receivable)</td>
                  <td class="mc-amount num-col">{{ ra.sundryDebtors | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td>Sundry Creditors (Bills Payable)</td>
                  <td class="mc-amount num-col">{{ ra.sundryCreditors | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td>Sales Accounts</td>
                  <td class="mc-amount num-col">{{ ra.salesAccounts | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td>Purchase Accounts</td>
                  <td class="mc-amount num-col">{{ ra.purchaseAccounts | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td>Stock-in-Hand</td>
                  <td class="mc-amount num-col">{{ ra.stockInHand | mcIndianCurrency }}</td>
                </tr>
                <tr>
                  <td><strong>Net Profit</strong></td>
                  <td class="mc-amount num-col"><strong>{{ ra.netProfit | mcIndianCurrency }}</strong></td>
                </tr>
              </tbody>
            </table>
          </section>

          <section class="mc-card pane-card">
            <header class="pane-head">
              <h2>Principal Ratios</h2>
            </header>
            <table class="mc-table">
              <tbody>
                <tr>
                  <td><strong>Current Ratio</strong> (Current Assets : Current Liabilities)</td>
                  <td class="mc-amount num-col" data-testid="ratio-current">
                    <strong>{{ ra.currentRatio }} : 1</strong>
                  </td>
                </tr>
                <tr>
                  <td><strong>Quick Ratio</strong> (Current Assets &#x2212; Stock : Current Liabilities)</td>
                  <td class="mc-amount num-col" data-testid="ratio-quick">
                    <strong>{{ ra.quickRatio }} : 1</strong>
                  </td>
                </tr>
                <tr>
                  <td>Debt / Equity Ratio</td>
                  <td class="mc-amount num-col">{{ ra.debtEquityRatio }} : 1</td>
                </tr>
                <tr>
                  <td>Gross Profit %</td>
                  <td class="mc-amount num-col" data-testid="ratio-gp-pct">{{ ra.grossProfitPct }} %</td>
                </tr>
                <tr>
                  <td>Net Profit %</td>
                  <td class="mc-amount num-col" data-testid="ratio-np-pct">{{ ra.netProfitPct }} %</td>
                </tr>
                <tr>
                  <td>Return on Working Capital %</td>
                  <td class="mc-amount num-col">{{ ra.returnOnWorkingCapitalPct }} %</td>
                </tr>
                <tr>
                  <td>Inventory Turnover</td>
                  <td class="mc-amount num-col">{{ ra.inventoryTurnover }} times</td>
                </tr>
                <tr>
                  <td>Receivable Turnover in Days (Payment Performance)</td>
                  <td class="mc-amount num-col">{{ ra.receivableTurnoverDays }} days</td>
                </tr>
              </tbody>
            </table>
          </section>
        </div>
      }

      <footer class="mc-card cfr-footer">
        <a routerLink="/app/reports/balance-sheet" class="mc-btn">Balance Sheet</a>
        <a routerLink="/app/reports/pnl" class="mc-btn">Profit &amp; Loss A/c</a>
        <a routerLink="/app/reports/bills-receivable" class="mc-btn">Bills Receivable</a>
      </footer>
    </div>
  `,
  styles: `
    .cfr-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .cfr-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .cfr-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.12rem;
      font-weight: 700;
    }

    .cfr-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .cfr-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .cfr-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    .state-box {
      padding: 2rem;
      text-align: center;
      color: #475569;
      font-weight: 500;
    }

    .kpi-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
      gap: 0.65rem;
    }

    .kpi-tile {
      padding: 0.75rem 0.95rem;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .kpi-lbl {
      font-size: 0.7rem;
      font-weight: 700;
      color: #64748b;
      text-transform: uppercase;
    }

    .kpi-val {
      font-size: 1.02rem;
      font-weight: 700;
      color: #0f172a;
    }

    .kpi-teal {
      color: #006d68;
    }

    .two-pane {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
    }

    @media (max-width: 900px) {
      .two-pane {
        grid-template-columns: 1fr;
      }
    }

    .pane-card {
      overflow: hidden;
    }

    .pane-head {
      padding: 0.6rem 0.95rem;
      background: #f6faf9;
      border-bottom: 1px solid #cbd5e1;
    }

    .pane-head h2 {
      margin: 0;
      font-size: 0.9rem;
      font-weight: 700;
      color: #142f42;
    }

    .num-col {
      text-align: right;
    }

    .empty-cell {
      padding: 1.2rem;
      text-align: center;
      color: #64748b;
      font-weight: 500;
    }

    .cfr-footer {
      padding: 0.6rem 1rem;
      display: flex;
      gap: 0.45rem;
    }
  `,
})
export class CashFlowAndRatiosComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);
  readonly initialTab = input<CashFlowRatioTab | null>(null);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly route = inject(ActivatedRoute, { optional: true });

  readonly activeTab = signal<CashFlowRatioTab>('RATIOS');
  readonly fromDate = signal<string>(this.companyContext.periodFrom());
  readonly toDate = signal<string>(this.companyContext.periodTo());
  readonly loading = signal<boolean>(false);
  readonly cashFlow = signal<CashFlowReportDto | null>(null);
  readonly ratios = signal<RatioAnalysisReportDto | null>(null);

  ngOnInit(): void {
    const tabFromInput = this.initialTab();
    const tabFromRoute = this.route?.snapshot.data['viewMode'] as CashFlowRatioTab | undefined;
    if (tabFromInput) {
      this.activeTab.set(tabFromInput);
    } else if (tabFromRoute === 'CASH_FLOW' || tabFromRoute === 'RATIOS') {
      this.activeTab.set(tabFromRoute);
    }
    void this.reload();
  }

  switchTab(next: CashFlowRatioTab): void {
    this.activeTab.set(next);
    void this.reload();
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.loading.set(true);
    try {
      if (this.activeTab() === 'CASH_FLOW') {
        const cf = await this.reportsService.getCashFlow(companyId, {
          fromDate: this.fromDate(),
          toDate: this.toDate(),
        });
        this.cashFlow.set(cf);
      } else {
        const ra = await this.reportsService.getRatios(companyId, {
          fromDate: this.fromDate(),
          toDate: this.toDate(),
        });
        this.ratios.set(ra);
      }
    } catch {
      this.cashFlow.set(null);
      this.ratios.set(null);
    } finally {
      this.loading.set(false);
    }
  }
}
