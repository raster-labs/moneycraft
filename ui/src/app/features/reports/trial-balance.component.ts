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
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
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
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
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
          <button type="button" class="mc-btn" (click)="openPeriodModal()">
            F2: Period
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-primary"
            data-testid="tb-print-pdf-btn"
            (click)="exportPdf()"
          >
            Print PDF (Ctrl+P)
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
                  @if (autoColumnar() || detailedMode()) {
                    <th class="num-col">Opening Dr</th>
                    <th class="num-col">Opening Cr</th>
                    <th class="num-col">Transactions Dr</th>
                    <th class="num-col">Transactions Cr</th>
                  }
                  <th class="num-col">Closing Debit</th>
                  <th class="num-col">Closing Credit</th>
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
                    @if (autoColumnar() || detailedMode()) {
                      <td class="mc-amount num-col">
                        {{ isNonZero(row.openingDebit) ? (row.openingDebit | mcIndianCurrency) : '' }}
                      </td>
                      <td class="mc-amount num-col">
                        {{ isNonZero(row.openingCredit) ? (row.openingCredit | mcIndianCurrency) : '' }}
                      </td>
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
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="autoColumnar() || detailedMode() ? 8 : 4" class="empty-cell">
                      No group or ledger balances recorded in selected period.
                    </td>
                  </tr>
                }

                @if (isNonZero(r.unadjustedForexDebit) || isNonZero(r.unadjustedForexCredit)) {
                  <tr class="forex-row" data-testid="tb-unadjusted-forex-row">
                    <td><em>Unadjusted Forex Gain / Loss</em></td>
                    <td><span class="type-tag">FOREX</span></td>
                    @if (autoColumnar() || detailedMode()) {
                      <td class="num-col"></td>
                      <td class="num-col"></td>
                      <td class="num-col"></td>
                      <td class="num-col"></td>
                    }
                    <td class="mc-amount num-col">
                      {{ isNonZero(r.unadjustedForexDebit) ? (r.unadjustedForexDebit | mcIndianCurrency) : '' }}
                    </td>
                    <td class="mc-amount num-col">
                      {{ isNonZero(r.unadjustedForexCredit) ? (r.unadjustedForexCredit | mcIndianCurrency) : '' }}
                    </td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr class="grand-total-row">
                  <td colspan="2">
                    <strong>Grand Total</strong>
                    @if (r.isBalanced) {
                      <span class="mc-badge mc-badge-jade" data-testid="tb-balanced-badge">
                        Balanced (0.00 Diff)
                      </span>
                    } @else {
                      <span class="diff-warn" data-testid="tb-diff-badge">
                        Diff: {{ r.differenceAmount | mcIndianCurrency }}
                      </span>
                    }
                  </td>
                  @if (autoColumnar() || detailedMode()) {
                    <td class="mc-amount num-col">
                      <strong>{{ r.totalOpeningDebit | mcIndianCurrency }}</strong>
                    </td>
                    <td class="mc-amount num-col">
                      <strong>{{ r.totalOpeningCredit | mcIndianCurrency }}</strong>
                    </td>
                    <td class="mc-amount num-col">
                      <strong>{{ r.totalPeriodDebit | mcIndianCurrency }}</strong>
                    </td>
                    <td class="mc-amount num-col">
                      <strong>{{ r.totalPeriodCredit | mcIndianCurrency }}</strong>
                    </td>
                  }
                  <td class="mc-amount num-col" data-testid="tb-total-closing-debit">
                    <strong>{{ r.totalClosingDebit | mcIndianCurrency }}</strong>
                  </td>
                  <td class="mc-amount num-col" data-testid="tb-total-closing-credit">
                    <strong>{{ r.totalClosingCredit | mcIndianCurrency }}</strong>
                  </td>
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
              <button type="submit" class="mc-btn mc-btn-primary">Apply (Enter)</button>
            </div>
          </form>
        </div>
      }
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

  readonly groupDrillStack = signal<readonly TbGroupDrillStep[]>([]);

  draftFrom = '';
  draftTo = '';

  readonly activeGroupDrill = computed<TbGroupDrillStep | null>(() => {
    const stack = this.groupDrillStack();
    return stack.length > 0 ? stack[stack.length - 1] : null;
  });

  readonly displayRows = computed<readonly TrialBalanceRowDto[]>(() => {
    const r = this.report();
    if (!r) return [];

    const drill = this.activeGroupDrill();
    if (drill) {
      const matchedGroup = r.groupRows.find((g) => g.id === drill.groupId || g.name === drill.groupName);
      if (matchedGroup && matchedGroup.children.length > 0) {
        return matchedGroup.children;
      }
      const byParent = r.ledgerRows.filter(
        (l) => l.parentGroupId === drill.groupId || l.groupName === drill.groupName,
      );
      return byParent.length > 0 ? byParent : r.ledgerRows;
    }

    if (this.viewMode() === 'LEDGER') {
      return r.ledgerRows.length > 0 ? r.ledgerRows : r.groupRows;
    }

    const baseGroups = r.groupRows.length > 0 ? r.groupRows : r.ledgerRows;
    if (!this.detailedMode()) {
      return baseGroups;
    }

    const expanded: TrialBalanceRowDto[] = [];
    for (const g of baseGroups) {
      expanded.push(g);
      for (const child of g.children) {
        expanded.push({ ...child, depthLevel: Math.max(1, g.depthLevel + 1) });
      }
    }
    return expanded;
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
      this.shortcutRegistry.bindHandler(
        'nav.escape',
        'MODAL',
        () => {
          this.popGroupDrill();
        },
        () => this.groupDrillStack().length > 0,
      ),
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
      const data = await this.reportsService.getTrialBalance(companyId, {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        includeOptional: this.includeOptional(),
      });
      this.report.set(data);
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

    if (event.altKey || event.ctrlKey || event.metaKey) return;

    const rows = this.displayRows();
    if (rows.length === 0) return;

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
