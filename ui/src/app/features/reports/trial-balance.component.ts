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
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { ReportOutputModalComponent } from '../../shared/components/report-output-modal/report-output-modal.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { TrialBalanceReportDto, TrialBalanceRowDto } from './reports.models';
import { ReportsService } from './reports.service';

export const TB_GROUP_DRILL_ROUTE_TOKEN = '__tb_group_drill__';

interface TbGroupDrillStep {
  readonly groupId: string;
  readonly groupName: string;
  readonly priorSelectedIndex: number;
}

@Component({
  selector: 'mc-trial-balance',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    CodeBadgeComponent,
    IndianCurrencyPipe,
    SemanticIconComponent,
    ReportOutputModalComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tb-page">
      <header class="mc-card tb-header">
        <div class="tb-title-block">
          <div class="tb-pill-row">
            <span class="tb-pill">Financial Statements &bull; Trial Balance</span>
            @if (activeGroupDrill(); as grp) {
              <span class="tb-drill-chip" data-testid="tb-active-group-drill">
                Group: {{ grp.groupName }} (Esc = Up)
              </span>
            }
            @if (includeOptional()) {
              <span class="tb-opt-chip">Includes Optional Vouchers</span>
            }
          </div>
          <h1>Trial Balance</h1>
          <p class="tb-sub">
            Period <span class="mc-code">{{ fromDate() }}</span> to
            <span class="mc-code">{{ toDate() }}</span> &bull;
            {{ viewMode() === 'GROUP' ? 'Grouped (F5 = Ledger-wise)' : 'Ledger-wise (F5 = Grouped)' }}
            &bull; {{ detailedMode() ? 'Detailed (Alt+F1)' : 'Condensed (Alt+F1)' }}
          </p>
        </div>

        <div class="tb-actions">
          <button
            type="button"
            class="mc-btn"
            data-testid="tb-toggle-view-btn"
            (click)="toggleViewMode()"
          >
            {{ viewMode() === 'GROUP' ? 'F5: Ledger-wise' : 'F5: Grouped' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="tb-toggle-detailed-btn"
            (click)="toggleDetailed()"
          >
            Alt+F1: {{ detailedMode() ? 'Condensed' : 'Detailed' }}
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="tb-auto-column-btn"
            [class.mc-btn-primary]="autoColumnar()"
            (click)="toggleAutoColumnar()"
          >
            Alt+N: Auto-Column
          </button>
          <button
            type="button"
            class="mc-btn"
            data-testid="tb-basis-btn"
            (click)="toggleIncludeOptional()"
          >
            Ctrl+B: {{ includeOptional() ? 'Postings Only' : 'Incl. Optional' }}
          </button>
          <button type="button" class="mc-btn mc-btn-secondary" (click)="openPeriodModal()">
            <mc-icon name="calendar" [size]="16" />
            <span>F2: Period</span>
          </button>
          @if (removedRowKeys().length > 0) {
            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="tb-restore-line-btn"
              (click)="restoreLastRemovedRow()"
            >
              Restore Hidden ({{ removedRowKeys().length }}) &bull; Alt+U
            </button>
          }
          <button
            type="button"
            class="mc-btn mc-btn-secondary"
            data-testid="tb-output-btn"
            (click)="outputModalOpen.set(true)"
          >
            <mc-icon name="export" [size]="16" />
            <span>Output</span>
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="tb-print-pdf-btn"
            (click)="exportPdf()"
          >
            <mc-icon name="print" [size]="16" />
            <span>Print PDF (Ctrl+P)</span>
          </button>
        </div>
      </header>

      @if (statusMessage(); as msg) {
        <div class="tb-banner" data-testid="tb-status-banner" role="status">{{ msg }}</div>
      }

      @if (loading()) {
        <div class="mc-card state-box">Computing double-entry Trial Balance...</div>
      } @else if (report(); as r) {
        <section class="mc-card tb-grid-card">
          <div class="table-scroll">
            <table class="mc-table tb-table" data-testid="tb-table">
              <thead>
                <tr>
                  <th>Particulars</th>
                  <th>Type</th>
                  @if (showOpeningColumns()) {
                    <th class="num-col" data-testid="tb-th-opening-dr">Opening Dr</th>
                    <th class="num-col" data-testid="tb-th-opening-cr">Opening Cr</th>
                  }
                  @if (showTransactionColumns()) {
                    <th class="num-col" data-testid="tb-th-transactions-dr">Transactions Dr</th>
                    <th class="num-col" data-testid="tb-th-transactions-cr">Transactions Cr</th>
                  }
                  <th class="num-col">Closing Debit</th>
                  <th class="num-col">Closing Credit</th>
                  @if (showPercentageColumn()) {
                    <th class="num-col" data-testid="tb-th-percentage">% of Total</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of displayRows(); track row.id + '-' + row.rowType; let idx = $index) {
                  <tr
                    class="tb-row"
                    [class.tb-row-selected]="selectedIndex() === idx"
                    [class.tb-row-group]="row.rowType === 'GROUP'"
                    [class.tb-row-child]="row.depthLevel > 0"
                    [attr.data-testid]="'tb-row-' + row.id"
                    (click)="selectRow(idx)"
                    (dblclick)="drillDownRow(row)"
                  >
                    <td>
                      <span [class.indent-1]="row.depthLevel === 1" [class.indent-2]="row.depthLevel >= 2">
                        @if (row.rowType === 'GROUP') {
                          <strong>{{ row.name }}</strong>
                        } @else {
                          {{ row.name }}
                        }
                      </span>
                      @if (row.code) {
                        <mc-code-badge [code]="row.code" />
                      }
                    </td>
                    <td>
                      <span class="type-tag">{{ row.rowType }}</span>
                    </td>
                    @if (showOpeningColumns()) {
                      <td class="mc-amount num-col">
                        {{ isNonZero(row.openingDebit) ? (row.openingDebit | mcIndianCurrency) : '' }}
                      </td>
                      <td class="mc-amount num-col">
                        {{ isNonZero(row.openingCredit) ? (row.openingCredit | mcIndianCurrency) : '' }}
                      </td>
                    }
                    @if (showTransactionColumns()) {
                      <td class="mc-amount num-col">
                        {{ isNonZero(row.periodDebit) ? (row.periodDebit | mcIndianCurrency) : '' }}
                      </td>
                      <td class="mc-amount num-col">
                        {{ isNonZero(row.periodCredit) ? (row.periodCredit | mcIndianCurrency) : '' }}
                      </td>
                    }
                    <td class="mc-amount num-col" [attr.data-testid]="'tb-closing-dr-' + row.id">
                      {{ isNonZero(row.closingDebit) ? (row.closingDebit | mcIndianCurrency) : '' }}
                    </td>
                    <td class="mc-amount num-col" [attr.data-testid]="'tb-closing-cr-' + row.id">
                      {{ isNonZero(row.closingCredit) ? (row.closingCredit | mcIndianCurrency) : '' }}
                    </td>
                    @if (showPercentageColumn()) {
                      <td class="mc-amount num-col" [attr.data-testid]="'tb-pct-' + row.id">
                        {{ rowPercentage(row) }}%
                      </td>
                    }
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="totalColumnCount()" class="mc-empty-records">
                      No matching records
                    </td>
                  </tr>
                }

                @if (isNonZero(r.unadjustedForexDebit) || isNonZero(r.unadjustedForexCredit)) {
                  <tr class="forex-row" data-testid="tb-unadjusted-forex-row">
                    <td><em>Unadjusted Forex Gain / Loss</em></td>
                    <td><span class="type-tag">FOREX</span></td>
                    @if (showOpeningColumns()) {
                      <td class="num-col"></td>
                      <td class="num-col"></td>
                    }
                    @if (showTransactionColumns()) {
                      <td class="num-col"></td>
                      <td class="num-col"></td>
                    }
                    <td class="mc-amount num-col">
                      {{ isNonZero(r.unadjustedForexDebit) ? (r.unadjustedForexDebit | mcIndianCurrency) : '' }}
                    </td>
                    <td class="mc-amount num-col">
                      {{ isNonZero(r.unadjustedForexCredit) ? (r.unadjustedForexCredit | mcIndianCurrency) : '' }}
                    </td>
                    @if (showPercentageColumn()) {
                      <td class="num-col"></td>
                    }
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr class="grand-total-row">
                  <td colspan="2">
                    <strong>Grand Total</strong>
                    @if (effectiveTotals().isBalanced) {
                      <span class="mc-badge mc-badge-jade" data-testid="tb-balanced-badge">
                        Balanced (0.00 Diff)
                      </span>
                    } @else {
                      <span class="diff-warn" data-testid="tb-diff-badge">
                        Diff: {{ effectiveTotals().differenceAmount | mcIndianCurrency }}
                      </span>
                    }
                  </td>
                  @if (showOpeningColumns()) {
                    <td class="mc-amount num-col">
                      <strong>{{ effectiveTotals().totalOpeningDebit | mcIndianCurrency }}</strong>
                    </td>
                    <td class="mc-amount num-col">
                      <strong>{{ effectiveTotals().totalOpeningCredit | mcIndianCurrency }}</strong>
                    </td>
                  }
                  @if (showTransactionColumns()) {
                    <td class="mc-amount num-col">
                      <strong>{{ effectiveTotals().totalPeriodDebit | mcIndianCurrency }}</strong>
                    </td>
                    <td class="mc-amount num-col">
                      <strong>{{ effectiveTotals().totalPeriodCredit | mcIndianCurrency }}</strong>
                    </td>
                  }
                  <td class="mc-amount num-col" data-testid="tb-total-closing-debit">
                    <strong>{{ effectiveTotals().totalClosingDebit | mcIndianCurrency }}</strong>
                  </td>
                  <td class="mc-amount num-col" data-testid="tb-total-closing-credit">
                    <strong>{{ effectiveTotals().totalClosingCredit | mcIndianCurrency }}</strong>
                  </td>
                  @if (showPercentageColumn()) {
                    <td class="mc-amount num-col">
                      <strong>100.00%</strong>
                    </td>
                  }
                </tr>
              </tfoot>
            </table>
          </div>

          <footer class="tb-footer">
            <div class="nav-links">
              @if (activeGroupDrill()) {
                <button type="button" class="mc-btn" (click)="popGroupDrill()">
                  &#x2190; Back to Parent Trial Balance (Esc)
                </button>
              }
              <a routerLink="/app/reports/pnl" class="mc-btn">Profit &amp; Loss A/c</a>
              <a routerLink="/app/reports/balance-sheet" class="mc-btn">Balance Sheet</a>
              <a routerLink="/app/reports/schedule-iii" class="mc-btn">Schedule III</a>
            </div>
            <span class="hint-text">
              Enter = Progressive Drill-Down (Group &#x2192; Ledger &#x2192; Day Book) &bull; Esc = Pop &amp; Restore Focus
            </span>
          </footer>
        </section>
      }

      @if (periodModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Trial Balance Period"
          (keydown.escape)="periodModalOpen.set(false)"
        >
          <form class="mc-modal-dialog period-dialog" (ngSubmit)="applyPeriod()">
            <div class="mc-modal-header">
              <span>Trial Balance Period (F2)</span>
              <button type="button" class="mc-btn" (click)="periodModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body period-fields">
              <label class="field-col">
                <span>From Date</span>
                <input type="date" name="draftFrom" class="mc-input" [(ngModel)]="draftFrom" required />
              </label>
              <label class="field-col">
                <span>To Date</span>
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
        reportTitle="Trial Balance"
        [boundsLabel]="fromDate() + ' to ' + toDate()"
        (printRequested)="exportPdf()"
        (exportRequested)="exportPdf()"
        (closed)="outputModalOpen.set(false)"
      />
    </div>
  `,
  styles: `
    .tb-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .tb-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .tb-title-block h1 {
      margin: 0.2rem 0 0.1rem;
      font-size: 1.15rem;
      font-weight: 700;
    }

    .tb-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .tb-pill-row {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      flex-wrap: wrap;
    }

    .tb-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .tb-drill-chip,
    .tb-opt-chip {
      background: rgba(76, 225, 189, 0.16);
      border: 1px solid #4ce1bd;
      color: #4ce1bd;
      border-radius: 4px;
      padding: 0.08rem 0.45rem;
      font-size: 0.7rem;
      font-weight: 600;
    }

    .tb-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    .tb-banner {
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
      font-weight: 500;
      color: #475569;
    }

    .tb-grid-card {
      overflow: hidden;
    }

    .table-scroll {
      overflow-x: auto;
    }

    .num-col {
      text-align: right;
    }

    .tb-row {
      cursor: pointer;
    }

    .tb-row-group {
      background: #f8fafc;
    }

    .tb-row-selected {
      background: #e6f7f5;
      outline: 2px solid #008d87;
      outline-offset: -2px;
    }

    .indent-1 {
      padding-left: 1.2rem;
    }

    .indent-2 {
      padding-left: 2.2rem;
    }

    .type-tag {
      font-size: 0.68rem;
      font-weight: 700;
      padding: 0.1rem 0.38rem;
      border-radius: 4px;
      background: #f1f5f9;
      color: #334155;
    }

    .forex-row {
      background: #fffbeb;
    }

    .grand-total-row td {
      background: #f6faf9;
      border-top: 2px solid #142f42;
      padding-top: 0.6rem;
      padding-bottom: 0.6rem;
    }

    .diff-warn {
      margin-left: 0.5rem;
      color: #b91c1c;
      font-size: 0.75rem;
      font-weight: 700;
    }

    .empty-cell {
      text-align: center;
      padding: 1.5rem;
      color: #64748b;
      font-weight: 500;
    }

    .tb-footer {
      padding: 0.65rem 1rem;
      border-top: 1px solid #e2e8f0;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.5rem;
      background: #f8fafc;
    }

    .nav-links {
      display: flex;
      gap: 0.4rem;
      flex-wrap: wrap;
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
export class TrialBalanceComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly navStack = inject(NavigationStackStore);
  private readonly destroyRef = inject(DestroyRef);

  readonly fromDate = signal<string>(this.companyContext.periodFrom());
  readonly toDate = signal<string>(this.companyContext.periodTo());
  readonly includeOptional = signal<boolean>(false);
  readonly viewMode = signal<'GROUP' | 'LEDGER'>('GROUP');
  readonly detailedMode = signal<boolean>(false);
  readonly autoColumnar = signal<boolean>(false);

  readonly loading = signal<boolean>(false);
  readonly report = signal<TrialBalanceReportDto | null>(null);
  readonly selectedIndex = signal<number>(0);
  readonly statusMessage = signal<string | null>(null);
  readonly periodModalOpen = signal<boolean>(false);
  readonly outputModalOpen = signal<boolean>(false);
  readonly removedRowKeys = signal<readonly string[]>([]);

  readonly groupDrillStack = signal<readonly TbGroupDrillStep[]>([]);

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
              effect: 'Apply selected Trial Balance period.',
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
              effect: 'Close Trial Balance period dialog.',
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

  readonly activeGroupDrill = computed<TbGroupDrillStep | null>(() => {
    const stack = this.groupDrillStack();
    return stack.length > 0 ? stack[stack.length - 1] : null;
  });

  readonly showOpeningColumns = computed<boolean>(
    () => this.autoColumnar() || this.companyContext.f12Config().showOpeningBalances,
  );

  readonly showTransactionColumns = computed<boolean>(
    () => this.autoColumnar() || this.companyContext.f12Config().showTransactions,
  );

  readonly showPercentageColumn = computed<boolean>(
    () => this.companyContext.f12Config().showPercentages,
  );

  readonly totalColumnCount = computed<number>(() => {
    let cols = 3; // Particulars + Closing Dr + Closing Cr
    if (this.showOpeningColumns()) cols += 2;
    if (this.showTransactionColumns()) cols += 2;
    if (this.showPercentageColumn()) cols += 1;
    return cols;
  });

  readonly displayRows = computed<readonly TrialBalanceRowDto[]>(() => {
    const r = this.report();
    if (!r) return [];
    const hidden = new Set(this.removedRowKeys());

    const drill = this.activeGroupDrill();
    let candidateRows: readonly TrialBalanceRowDto[];
    if (drill) {
      const matchedGroup = r.groupRows.find((g) => g.id === drill.groupId || g.name === drill.groupName);
      if (matchedGroup && matchedGroup.children.length > 0) {
        candidateRows = matchedGroup.children;
      } else {
        const byParent = r.ledgerRows.filter(
          (l) => l.parentGroupId === drill.groupId || l.groupName === drill.groupName,
        );
        candidateRows = byParent.length > 0 ? byParent : r.ledgerRows;
      }
    } else if (this.viewMode() === 'LEDGER') {
      candidateRows = r.ledgerRows.length > 0 ? r.ledgerRows : r.groupRows;
    } else {
      const baseGroups = r.groupRows.length > 0 ? r.groupRows : r.ledgerRows;
      if (!this.detailedMode()) {
        candidateRows = baseGroups;
      } else {
        const expanded: TrialBalanceRowDto[] = [];
        for (const g of baseGroups) {
          expanded.push(g);
          for (const child of g.children) {
            expanded.push({ ...child, depthLevel: Math.max(1, g.depthLevel + 1) });
          }
        }
        candidateRows = expanded;
      }
    }

    return candidateRows.filter((row) => !hidden.has(`${row.rowType}:${row.id}`));
  });

  readonly effectiveTotals = computed(() => {
    const r = this.report();
    const scale = this.companyContext.currencyScale();
    if (!r) {
      const zero = ExactDecimal.zero(scale).toWireString(scale);
      return {
        totalOpeningDebit: zero,
        totalOpeningCredit: zero,
        totalPeriodDebit: zero,
        totalPeriodCredit: zero,
        totalClosingDebit: zero,
        totalClosingCredit: zero,
        differenceAmount: zero,
        isBalanced: true,
      };
    }
    if (this.removedRowKeys().length === 0) {
      return {
        totalOpeningDebit: r.totalOpeningDebit,
        totalOpeningCredit: r.totalOpeningCredit,
        totalPeriodDebit: r.totalPeriodDebit,
        totalPeriodCredit: r.totalPeriodCredit,
        totalClosingDebit: r.totalClosingDebit,
        totalClosingCredit: r.totalClosingCredit,
        differenceAmount: r.differenceAmount,
        isBalanced: r.isBalanced,
      };
    }
    const rows = this.displayRows().filter((row) => row.depthLevel === 0 || !this.detailedMode());
    let opDr = ExactDecimal.zero(scale);
    let opCr = ExactDecimal.zero(scale);
    let perDr = ExactDecimal.zero(scale);
    let perCr = ExactDecimal.zero(scale);
    let clDr = ExactDecimal.zero(scale);
    let clCr = ExactDecimal.zero(scale);

    for (const row of rows) {
      opDr = opDr.add(ExactDecimal.tryParse(row.openingDebit, scale, 'HALF_UP') ?? ExactDecimal.zero(scale));
      opCr = opCr.add(ExactDecimal.tryParse(row.openingCredit, scale, 'HALF_UP') ?? ExactDecimal.zero(scale));
      perDr = perDr.add(ExactDecimal.tryParse(row.periodDebit, scale, 'HALF_UP') ?? ExactDecimal.zero(scale));
      perCr = perCr.add(ExactDecimal.tryParse(row.periodCredit, scale, 'HALF_UP') ?? ExactDecimal.zero(scale));
      clDr = clDr.add(ExactDecimal.tryParse(row.closingDebit, scale, 'HALF_UP') ?? ExactDecimal.zero(scale));
      clCr = clCr.add(ExactDecimal.tryParse(row.closingCredit, scale, 'HALF_UP') ?? ExactDecimal.zero(scale));
    }
    const diff = clDr.sub(clCr).abs();
    return {
      totalOpeningDebit: opDr.toWireString(scale),
      totalOpeningCredit: opCr.toWireString(scale),
      totalPeriodDebit: perDr.toWireString(scale),
      totalPeriodCredit: perCr.toWireString(scale),
      totalClosingDebit: clDr.toWireString(scale),
      totalClosingCredit: clCr.toWireString(scale),
      differenceAmount: diff.toWireString(scale),
      isBalanced: clDr.equals(clCr),
    };
  });

  ngOnInit(): void {
    const disposers = [
      this.shortcutRegistry.bindHandler('voucher.payment', 'SCREEN', () => {
        this.toggleViewMode();
      }),
      this.shortcutRegistry.bindHandler('report.toggleDetailed', 'SCREEN', () => {
        this.toggleDetailed();
      }),
      this.shortcutRegistry.bindHandler('report.basisOfValues', 'SCREEN', () => {
        this.toggleIncludeOptional();
      }),
      this.shortcutRegistry.bindHandler('report.autoColumn', 'SCREEN', () => {
        this.toggleAutoColumnar();
      }),
      this.shortcutRegistry.bindHandler('context.changeDate', 'SCREEN', () => {
        this.openPeriodModal();
      }),
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        void this.exportPdf();
      }),
      this.shortcutRegistry.bindHandler('register.removeLine', 'SCREEN', () => {
        this.removeSelectedRow();
      }),
      this.shortcutRegistry.bindHandler('register.restoreLine', 'SCREEN', () => {
        this.restoreLastRemovedRow();
      }),
      this.shortcutRegistry.bindHandler('register.restoreAllLines', 'SCREEN', () => {
        this.removedRowKeys.set([]);
      }),
      this.shortcutRegistry.bindHandler('report.explodeRow', 'SCREEN', () => {
        this.toggleDetailed();
      }),
      this.shortcutRegistry.bindHandler('report.addColumn', 'SCREEN', () => {
        this.toggleAutoColumnar();
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
          if (this.groupDrillStack().length > 0) {
            this.popGroupDrill();
            return true;
          }
          return false;
        },
        () =>
          this.groupDrillStack().length > 0 ||
          this.periodModalOpen() ||
          this.outputModalOpen(),
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

  removeSelectedRow(): void {
    const rows = this.displayRows();
    if (rows.length === 0) return;
    const target = rows[Math.min(Math.max(0, this.selectedIndex()), rows.length - 1)];
    if (!target) return;
    this.removedRowKeys.set([...this.removedRowKeys(), `${target.rowType}:${target.id}`]);
    const nextLen = this.displayRows().length;
    if (this.selectedIndex() >= nextLen && nextLen > 0) {
      this.selectedIndex.set(nextLen - 1);
    }
  }

  restoreLastRemovedRow(): void {
    const stack = this.removedRowKeys();
    if (stack.length === 0) return;
    this.removedRowKeys.set(stack.slice(0, -1));
  }

  async reload(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;

    this.loading.set(true);
    try {
      const data = await this.reportsService.getTrialBalance(companyId, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        includeOptional: this.includeOptional(),
      });
      this.report.set(data);
      this.removedRowKeys.set([]);
      this.selectedIndex.set(0);
    } catch {
      this.report.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  toggleViewMode(): void {
    this.viewMode.set(this.viewMode() === 'GROUP' ? 'LEDGER' : 'GROUP');
    this.selectedIndex.set(0);
  }

  toggleDetailed(): void {
    this.detailedMode.set(!this.detailedMode());
    this.selectedIndex.set(0);
  }

  toggleAutoColumnar(): void {
    this.autoColumnar.set(!this.autoColumnar());
  }

  toggleIncludeOptional(): void {
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

  selectRow(idx: number): void {
    this.selectedIndex.set(idx);
  }

  drillDownRow(row: TrialBalanceRowDto): void {
    if (row.rowType === 'GROUP') {
      const priorIdx = this.selectedIndex();
      this.navStack.push({
        title: `Trial Balance Group: ${row.name}`,
        route: TB_GROUP_DRILL_ROUTE_TOKEN,
        params: { groupId: row.id, groupName: row.name },
      });
      this.groupDrillStack.set([
        ...this.groupDrillStack(),
        { groupId: row.id, groupName: row.name, priorSelectedIndex: priorIdx },
      ]);
      this.selectedIndex.set(0);
      return;
    }

    this.navStack.push({
      title: `Ledger Vouchers: ${row.name}`,
      route: '/app/daybook',
      params: { ledgerId: row.id, ledgerName: row.name },
    });
  }

  popGroupDrill(): void {
    const stack = this.groupDrillStack();
    if (stack.length === 0) return;
    const popped = stack[stack.length - 1];
    this.groupDrillStack.set(stack.slice(0, -1));
    if (this.navStack.topFrame()?.route === TB_GROUP_DRILL_ROUTE_TOKEN) {
      this.navStack.pop();
    }
    this.selectedIndex.set(popped.priorSelectedIndex);
  }

  async exportPdf(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    await this.reportsService.exportReportPdf(
      companyId,
      'trial-balance',
      {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        includeOptional: this.includeOptional(),
        detailed: this.detailedMode(),
      },
      `TrialBalance_${this.fromDate()}_${this.toDate()}.pdf`,
    );
    this.statusMessage.set('Trial Balance PDF generated cleanly.');
  }

  isNonZero(wire: string): boolean {
    const dec = ExactDecimal.tryParse(wire, 6, 'HALF_UP');
    return dec !== null && !dec.isZero();
  }

  rowPercentage(row: TrialBalanceRowDto): string {
    const totals = this.effectiveTotals();
    const rowDr = ExactDecimal.tryParse(row.closingDebit, 6, 'HALF_UP') ?? ExactDecimal.zero(6);
    const rowCr = ExactDecimal.tryParse(row.closingCredit, 6, 'HALF_UP') ?? ExactDecimal.zero(6);
    const isDr = !rowDr.isZero();
    const num = isDr ? rowDr : rowCr;
    const denWire = isDr ? totals.totalClosingDebit : totals.totalClosingCredit;
    const den = ExactDecimal.tryParse(denWire, 6, 'HALF_UP') ?? ExactDecimal.zero(6);
    if (num.isZero() || den.isZero()) {
      return '—';
    }
    const pct = num.mulScaled(ExactDecimal.fromInt(100, 2), 6, 'HALF_UP').divHalfUp(den, 2);
    return `${pct.toWireString(2)}%`;
  }

  stepPeriod(delta: 1 | -1): void {
    const addDays = (iso: string, days: number): string => {
      const d = new Date(`${iso}T00:00:00Z`);
      if (Number.isNaN(d.getTime())) return iso;
      d.setUTCDate(d.getUTCDate() + days);
      return d.toISOString().slice(0, 10);
    };
    const nextFrom = addDays(this.fromDate(), delta);
    const nextTo = addDays(this.toDate(), delta);
    this.fromDate.set(nextFrom);
    this.toDate.set(nextTo);
    this.statusMessage.set(`Period stepped to ${nextFrom} – ${nextTo}.`);
    void this.reload();
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (this.periodModalOpen() || isEditableTextElement(event.target)) return;
    if (
      this.navStack.hasOverlays() &&
      !this.embeddedOverlay() &&
      this.navStack.topFrame()?.route !== TB_GROUP_DRILL_ROUTE_TOKEN
    ) {
      return;
    }

    if (event.key === 'Escape' && this.groupDrillStack().length > 0) {
      event.preventDefault();
      this.popGroupDrill();
      return;
    }

    if (
      !event.altKey &&
      !event.ctrlKey &&
      !event.metaKey &&
      (event.key === '+' || event.key === '=')
    ) {
      event.preventDefault();
      this.stepPeriod(1);
      return;
    }
    if (
      !event.altKey &&
      !event.ctrlKey &&
      !event.metaKey &&
      (event.key === '-' || event.key === '_')
    ) {
      event.preventDefault();
      this.stepPeriod(-1);
      return;
    }

    const rows = this.displayRows();
    if (rows.length === 0) return;

    if (event.altKey && !event.ctrlKey && !event.metaKey && event.key === 'Enter') {
      event.preventDefault();
      this.toggleDetailed();
      return;
    }

    if (event.ctrlKey && !event.altKey && !event.metaKey && event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedIndex.set(0);
      return;
    }
    if (event.ctrlKey && !event.altKey && !event.metaKey && event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedIndex.set(rows.length - 1);
      return;
    }

    if (event.altKey || event.ctrlKey || event.metaKey) return;

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.selectedIndex.set(Math.min(rows.length - 1, this.selectedIndex() + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.selectedIndex.set(Math.max(0, this.selectedIndex() - 1));
    } else if (event.key === 'Home') {
      event.preventDefault();
      this.selectedIndex.set(0);
    } else if (event.key === 'End') {
      event.preventDefault();
      this.selectedIndex.set(rows.length - 1);
    } else if (event.key === 'Enter') {
      const target = rows[this.selectedIndex()];
      if (target) {
        event.preventDefault();
        this.drillDownRow(target);
      }
    }
  }
}
