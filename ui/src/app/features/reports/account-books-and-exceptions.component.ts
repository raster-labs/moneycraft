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
import { ActivatedRoute, Router } from '@angular/router';
import { isEditableTextElement } from '../../core/keyboard/collision-guard';
import {
  ActiveModalContextHandle,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../core/keyboard/shortcut-command.registry';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { ReportOutputModalComponent } from '../../shared/components/report-output-modal/report-output-modal.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { InventoryStore } from '../inventory/inventory.store';
import { ChartOfAccountsStore } from '../masters/chart-of-accounts.store';
import {
  AccountBookReportDto,
  AccountBookType,
  ExceptionReportDto,
  ExceptionReportType,
  StatisticsReportDto,
  StockQueryReportDto,
  TallyLevel3WorkbenchMode,
} from './reports.models';
import { ReportsService } from './reports.service';

const BOOK_TYPE_OPTIONS: readonly { readonly value: AccountBookType; readonly label: string }[] = [
  { value: 'CASH_BANK_SUMMARY', label: 'Cash / Bank Book(s)' },
  { value: 'GROUP_SUMMARY', label: 'Group Summary' },
  { value: 'GROUP_VOUCHERS', label: 'Group Vouchers' },
  { value: 'LEDGER_VOUCHERS', label: 'Ledger Vouchers' },
  { value: 'SALES_REGISTER', label: 'Sales Register' },
  { value: 'PURCHASE_REGISTER', label: 'Purchase Register' },
  { value: 'JOURNAL_REGISTER', label: 'Journal Register' },
  { value: 'CONTRA_REGISTER', label: 'Contra Register' },
  { value: 'PAYMENT_REGISTER', label: 'Payment Register' },
  { value: 'RECEIPT_REGISTER', label: 'Receipt Register' },
  { value: 'DEBIT_NOTE_REGISTER', label: 'Debit Note Register' },
  { value: 'CREDIT_NOTE_REGISTER', label: 'Credit Note Register' },
];

const EXCEPTION_TYPE_OPTIONS: readonly {
  readonly value: ExceptionReportType;
  readonly label: string;
}[] = [
  { value: 'NEGATIVE_STOCK', label: 'Negative Stock' },
  { value: 'NEGATIVE_LEDGERS', label: 'Negative Ledgers' },
  { value: 'OVERDUE_RECEIVABLES', label: 'Overdue Receivables' },
  { value: 'OVERDUE_PAYABLES', label: 'Overdue Payables' },
  { value: 'OPTIONAL_VOUCHERS', label: 'Optional Vouchers' },
  { value: 'CANCELLED_VOUCHERS', label: 'Cancelled Vouchers' },
  { value: 'MEMORANDUM_VOUCHERS', label: 'Memorandum Vouchers' },
  { value: 'REVERSING_JOURNALS', label: 'Reversing Journals' },
  { value: 'APPROVAL_QUEUE', label: 'Maker-Checker Approval Queue' },
];

@Component({
  selector: 'mc-account-books-and-exceptions',
  standalone: true,
  imports: [
    FormsModule,
    CodeBadgeComponent,
    IndianCurrencyPipe,
    ReportOutputModalComponent,
    SemanticIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-l3-shell" data-testid="level3-reports-workbench">
      <header class="mc-card mc-l3-header">
        <div>
          <div class="mc-l3-title-row">
            <h1 class="mc-l3-title" data-testid="level3-report-title">{{ headingTitle() }}</h1>
            <mc-code-badge [code]="fromDate() + ' .. ' + toDate()" />
            @if (removedRowIds().length > 0) {
              <span class="mc-badge mc-badge-slate">
                {{ removedRowIds().length }} Hidden (Alt+U / Ctrl+U)
              </span>
            }
          </div>
          <p class="mc-l3-subtitle">
            Display More Reports &bull; Live PostgreSQL 18 Exact BigDecimal Projection
          </p>
        </div>

        <div class="mc-l3-toolbar">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="mode() === 'ACCOUNT_BOOKS'"
            data-testid="l3-tab-account-books"
            (click)="switchMode('ACCOUNT_BOOKS')"
          >
            Account Books
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="mode() === 'EXCEPTIONS'"
            data-testid="l3-tab-exceptions"
            (click)="switchMode('EXCEPTIONS')"
          >
            Exception Reports
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="mode() === 'STATISTICS'"
            data-testid="l3-tab-statistics"
            (click)="switchMode('STATISTICS')"
          >
            Statistics
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="mode() === 'STOCK_QUERY'"
            data-testid="l3-tab-stock-query"
            (click)="switchMode('STOCK_QUERY')"
          >
            Stock Query
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-secondary"
            data-testid="l3-period-btn"
            (click)="openPeriodDialog()"
          >
            <mc-icon name="calendar" [size]="16" />
            <span>Period (F2)</span>
          </button>
          <button
            type="button"
            class="mc-btn mc-btn-secondary"
            data-testid="l3-output-btn"
            (click)="outputModalOpen.set(true)"
          >
            <mc-icon name="print" [size]="16" />
            <span>Output</span>
          </button>
        </div>
      </header>

      <!-- Sub-Selector Bar -->
      <section class="mc-card mc-l3-filter-bar">
        @if (mode() === 'ACCOUNT_BOOKS') {
          <div class="mc-l3-filter-group">
            <label class="mc-field-label" for="mc-l3-book-select">Book / Register</label>
            <select
              id="mc-l3-book-select"
              class="mc-select"
              data-testid="l3-book-type-select"
              [ngModel]="bookType()"
              (ngModelChange)="onBookTypeChange($event)"
            >
              @for (opt of bookOptions; track opt.value) {
                <option [value]="opt.value">{{ opt.label }}</option>
              }
            </select>
          </div>
          @if (bookType() === 'GROUP_SUMMARY' || bookType() === 'GROUP_VOUCHERS') {
            <div class="mc-l3-filter-group">
              <label class="mc-field-label" for="mc-l3-grp-select">Account Group</label>
              <select
                id="mc-l3-grp-select"
                class="mc-select"
                [ngModel]="selectedGroupId()"
                (ngModelChange)="onGroupChange($event)"
              >
                <option value="">All Primary Groups</option>
                @for (grp of coaStore.groups(); track grp.id) {
                  <option [value]="grp.id">{{ grp.name }}</option>
                }
              </select>
            </div>
          }
          @if (bookType() === 'LEDGER_VOUCHERS') {
            <div class="mc-l3-filter-group">
              <label class="mc-field-label" for="mc-l3-ldg-select">Ledger Account</label>
              <select
                id="mc-l3-ldg-select"
                class="mc-select"
                [ngModel]="selectedLedgerId()"
                (ngModelChange)="onLedgerChange($event)"
              >
                <option value="">Default Ledger</option>
                @for (ldg of coaStore.ledgers(); track ldg.id) {
                  <option [value]="ldg.id">{{ ldg.name }}</option>
                }
              </select>
            </div>
          }
        } @else if (mode() === 'EXCEPTIONS') {
          <div class="mc-l3-filter-group">
            <label class="mc-field-label" for="mc-l3-exc-select">Exception View</label>
            <select
              id="mc-l3-exc-select"
              class="mc-select"
              data-testid="l3-exception-type-select"
              [ngModel]="exceptionType()"
              (ngModelChange)="onExceptionTypeChange($event)"
            >
              @for (opt of exceptionOptions; track opt.value) {
                <option [value]="opt.value">{{ opt.label }}</option>
              }
            </select>
          </div>
        } @else if (mode() === 'STOCK_QUERY') {
          <div class="mc-l3-filter-group">
            <label class="mc-field-label" for="mc-l3-item-select">Stock Item (360° Query)</label>
            <select
              id="mc-l3-item-select"
              class="mc-select"
              data-testid="l3-stock-item-select"
              [ngModel]="selectedStockItemId()"
              (ngModelChange)="onStockItemChange($event)"
            >
              @for (it of inventoryStore.stockItems(); track it.id) {
                <option [value]="it.id">{{ it.name }} ({{ it.baseUomSymbol }})</option>
              }
            </select>
          </div>
        }

        <div class="mc-l3-filter-actions">
          <button type="button" class="mc-btn" (click)="reload()">
            <mc-icon name="refresh" [size]="16" />
            <span>Refresh</span>
          </button>
        </div>
      </section>

      @if (errorBanner(); as err) {
        <div class="mc-status-banner mc-status-error" role="alert">
          <mc-icon name="error" [size]="20" />
          <span>{{ err }}</span>
        </div>
      }

      <!-- 1. ACCOUNT BOOKS VIEW -->
      @if (mode() === 'ACCOUNT_BOOKS') {
        <section class="mc-card mc-l3-card" aria-label="Account Books and Registers">
          @if (accountBookReport(); as ab) {
            @if (ab.monthlySummary.length > 0 && detailedMode()) {
              <div class="mc-l3-subhead">Monthly Turnover Summary</div>
              <div class="mc-l3-table-wrap">
                <table class="mc-l3-table">
                  <thead>
                    <tr>
                      <th scope="col">Month</th>
                      <th scope="col" class="mc-col-amt">Vouchers</th>
                      <th scope="col" class="mc-col-amt">Debit</th>
                      <th scope="col" class="mc-col-amt">Credit</th>
                      <th scope="col" class="mc-col-amt">Closing</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (m of ab.monthlySummary; track m.monthLabel) {
                      <tr>
                        <td>{{ m.monthLabel }}</td>
                        <td class="mc-amount mc-col-amt">{{ m.voucherCount }}</td>
                        <td class="mc-amount mc-col-amt">
                          {{ m.debitAmount | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ m.creditAmount | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                        <td class="mc-amount mc-col-amt">
                          {{ m.closingBalance | mcIndianCurrency: companyContext.currencyScale() }}
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }

            <div class="mc-l3-table-wrap">
              <table class="mc-l3-table" data-testid="l3-account-books-table">
                <thead>
                  <tr>
                    <th scope="col">Date</th>
                    <th scope="col">Particulars</th>
                    <th scope="col">Voucher Type</th>
                    <th scope="col">Vch No.</th>
                    <th scope="col" class="mc-col-amt">Debit</th>
                    <th scope="col" class="mc-col-amt">Credit</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of visibleAccountBookRows(); track row.id; let idx = $index) {
                    <tr
                      [class.mc-l3-row-selected]="idx === selectedIndex()"
                      (click)="selectedIndex.set(idx)"
                      (dblclick)="openVoucherIfPresent(row.voucherId)"
                    >
                      <td class="mc-code">{{ row.rowDate || '—' }}</td>
                      <td>{{ row.particulars }}</td>
                      <td>{{ row.voucherTypeName }}</td>
                      <td class="mc-code">{{ row.voucherNumber || '—' }}</td>
                      <td class="mc-amount mc-col-amt">
                        {{ row.debitAmount | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                      <td class="mc-amount mc-col-amt">
                        {{ row.creditAmount | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="mc-empty-records">No matching records</td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr class="mc-l3-total-row">
                    <td colspan="4"><strong>Total</strong></td>
                    <td class="mc-amount mc-col-amt">
                      {{ ab.totalDebit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td class="mc-amount mc-col-amt">
                      {{ ab.totalCredit | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          } @else {
            <div class="mc-empty-records">No matching records</div>
          }
        </section>
      }

      <!-- 2. EXCEPTION REPORTS VIEW -->
      @if (mode() === 'EXCEPTIONS') {
        <section class="mc-card mc-l3-card" aria-label="Exception Reports">
          <div class="mc-l3-table-wrap">
            <table class="mc-l3-table" data-testid="l3-exceptions-table">
              <thead>
                <tr>
                  <th scope="col">Date</th>
                  <th scope="col">Code / Vch No.</th>
                  <th scope="col">Particulars / Item / Ledger</th>
                  <th scope="col">Category</th>
                  <th scope="col" class="mc-col-amt">Qty</th>
                  <th scope="col" class="mc-col-amt">Amount</th>
                  <th scope="col">State</th>
                </tr>
              </thead>
              <tbody>
                @for (row of visibleExceptionRows(); track row.id; let idx = $index) {
                  <tr
                    [class.mc-l3-row-selected]="idx === selectedIndex()"
                    (click)="selectedIndex.set(idx)"
                    (dblclick)="openVoucherIfPresent(row.voucherId)"
                  >
                    <td class="mc-code">{{ row.rowDate || '—' }}</td>
                    <td class="mc-code">{{ row.code || '—' }}</td>
                    <td>{{ row.name }}</td>
                    <td>{{ row.secondaryLabel }}</td>
                    <td class="mc-amount mc-col-amt">{{ row.quantity }}</td>
                    <td class="mc-amount mc-col-amt">
                      {{ row.amount | mcIndianCurrency: companyContext.currencyScale() }}
                    </td>
                    <td><span class="mc-badge mc-badge-slate">{{ row.status }}</span></td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="7" class="mc-empty-records">No matching records</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }

      <!-- 3. STATISTICS TWO-PANE VIEW -->
      @if (mode() === 'STATISTICS') {
        <div class="mc-l3-two-pane" data-testid="l3-statistics-panes">
          <section class="mc-card mc-l3-card" aria-label="Types of Vouchers Statistics">
            <div class="mc-l3-subhead">
              <span>Types of Vouchers</span>
              <span>Total: {{ statisticsReport()?.totalVouchers ?? 0 }}</span>
            </div>
            <div class="mc-l3-table-wrap">
              <table class="mc-l3-table">
                <thead>
                  <tr>
                    <th scope="col">Type of Voucher</th>
                    <th scope="col" class="mc-col-amt">Posted</th>
                    <th scope="col" class="mc-col-amt">Optional</th>
                    <th scope="col" class="mc-col-amt">Cancelled</th>
                    <th scope="col" class="mc-col-amt">Total</th>
                  </tr>
                </thead>
                <tbody>
                  @for (v of statisticsReport()?.voucherTypeCounts ?? []; track v.voucherTypeName) {
                    <tr>
                      <td>{{ v.voucherTypeName }}</td>
                      <td class="mc-amount mc-col-amt">{{ v.postedCount }}</td>
                      <td class="mc-amount mc-col-amt">{{ v.optionalCount }}</td>
                      <td class="mc-amount mc-col-amt">{{ v.cancelledCount }}</td>
                      <td class="mc-amount mc-col-amt"><strong>{{ v.totalCount }}</strong></td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="5" class="mc-empty-records">No matching records</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>

          <section class="mc-card mc-l3-card" aria-label="Types of Accounts Statistics">
            <div class="mc-l3-subhead">
              <span>Types of Accounts &amp; Inventory Masters</span>
              <span>Total: {{ statisticsReport()?.totalMasters ?? 0 }}</span>
            </div>
            <div class="mc-l3-table-wrap">
              <table class="mc-l3-table">
                <thead>
                  <tr>
                    <th scope="col">Type of Master</th>
                    <th scope="col" class="mc-col-amt">Count</th>
                  </tr>
                </thead>
                <tbody>
                  @for (m of statisticsReport()?.masterCounts ?? []; track m.masterType) {
                    <tr>
                      <td>{{ m.masterType }}</td>
                      <td class="mc-amount mc-col-amt"><strong>{{ m.count }}</strong></td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="2" class="mc-empty-records">No matching records</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        </div>
      }

      <!-- 4. STOCK QUERY 360° VIEW -->
      @if (mode() === 'STOCK_QUERY') {
        <section class="mc-card mc-l3-card" aria-label="Stock Query 360 View">
          @if (stockQueryReport(); as sq) {
            <div class="mc-l3-subhead">
              <span>{{ sq.stockItemName }} ({{ sq.uomSymbol }})</span>
              <span class="mc-code">Valuation: {{ sq.costingMethod }}</span>
            </div>
            <div class="mc-l3-kpi-grid">
              <div class="mc-l3-kpi">
                <span class="mc-field-label">Opening Balance</span>
                <strong class="mc-amount">
                  {{ sq.openingQty }} {{ sq.uomSymbol }} ({{ sq.openingValue | mcIndianCurrency: companyContext.currencyScale() }})
                </strong>
              </div>
              <div class="mc-l3-kpi">
                <span class="mc-field-label">Total Inwards</span>
                <strong class="mc-amount">
                  {{ sq.inwardQty }} {{ sq.uomSymbol }} ({{ sq.inwardValue | mcIndianCurrency: companyContext.currencyScale() }})
                </strong>
              </div>
              <div class="mc-l3-kpi">
                <span class="mc-field-label">Total Outwards</span>
                <strong class="mc-amount">
                  {{ sq.outwardQty }} {{ sq.uomSymbol }} ({{ sq.outwardValue | mcIndianCurrency: companyContext.currencyScale() }})
                </strong>
              </div>
              <div class="mc-l3-kpi">
                <span class="mc-field-label">Closing Balance</span>
                <strong class="mc-amount">
                  {{ sq.closingQty }} {{ sq.uomSymbol }} ({{ sq.closingValue | mcIndianCurrency: companyContext.currencyScale() }})
                </strong>
              </div>
              <div class="mc-l3-kpi">
                <span class="mc-field-label">Last Purchase</span>
                <span>
                  {{ sq.lastPurchaseParty }} @ {{ sq.lastPurchaseRate | mcIndianCurrency: companyContext.currencyScale() }}
                </span>
              </div>
              <div class="mc-l3-kpi">
                <span class="mc-field-label">Last Sale</span>
                <span>
                  {{ sq.lastSalesParty }} @ {{ sq.lastSalesRate | mcIndianCurrency: companyContext.currencyScale() }}
                </span>
              </div>
            </div>

            <div class="mc-l3-table-wrap">
              <table class="mc-l3-table">
                <thead>
                  <tr>
                    <th scope="col">Godown / Location</th>
                    <th scope="col">Batch</th>
                    <th scope="col" class="mc-col-amt">Closing Qty</th>
                    <th scope="col" class="mc-col-amt">Closing Value</th>
                  </tr>
                </thead>
                <tbody>
                  @for (gd of sq.godownBreakup; track gd.godownName + gd.batchName) {
                    <tr>
                      <td>{{ gd.godownName }}</td>
                      <td class="mc-code">{{ gd.batchName }}</td>
                      <td class="mc-amount mc-col-amt">{{ gd.closingQty }}</td>
                      <td class="mc-amount mc-col-amt">
                        {{ gd.closingValue | mcIndianCurrency: companyContext.currencyScale() }}
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="4" class="mc-empty-records">No matching records</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <div class="mc-empty-records">No matching records</div>
          }
        </section>
      }

      @if (periodModalOpen()) {
        <div class="mc-modal-backdrop" role="dialog" aria-modal="true" aria-label="Change Report Period">
          <form class="mc-modal-dialog mc-l3-period-modal" (ngSubmit)="applyPeriod()">
            <div class="mc-modal-header">
              <span>Change Report Period</span>
              <button type="button" class="mc-btn mc-btn-secondary" (click)="periodModalOpen.set(false)">
                <mc-icon name="close" [size]="16" />
                <span>Close</span>
              </button>
            </div>
            <div class="mc-modal-body mc-l3-period-fields">
              <div>
                <label class="mc-field-label" for="mc-l3-from">From</label>
                <input id="mc-l3-from" type="date" name="draftFrom" class="mc-input" [(ngModel)]="draftFrom" required />
              </div>
              <div>
                <label class="mc-field-label" for="mc-l3-to">To</label>
                <input id="mc-l3-to" type="date" name="draftTo" class="mc-input" [(ngModel)]="draftTo" required />
              </div>
            </div>
            <div class="mc-modal-footer">
              <button type="submit" class="mc-btn mc-btn-primary">
                <mc-icon name="calendar" [size]="16" />
                <span>Apply</span>
              </button>
            </div>
          </form>
        </div>
      }

      <mc-report-output-modal
        [open]="outputModalOpen()"
        [reportTitle]="headingTitle()"
        [boundsLabel]="fromDate() + ' .. ' + toDate()"
        (printRequested)="exportPdf()"
        (exportRequested)="exportPdf()"
        (closed)="outputModalOpen.set(false)"
      />
    </div>
  `,
  styles: `
    .mc-l3-shell {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-l3-header,
    .mc-l3-filter-bar {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
    }

    .mc-l3-title-row,
    .mc-l3-toolbar,
    .mc-l3-filter-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-l3-title {
      margin: 0;
      font-size: var(--mc-font-size-lg);
      font-weight: 700;
      color: var(--mc-color-slate-900);
    }

    .mc-l3-subtitle {
      margin: 2px 0 0;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-l3-filter-group {
      min-width: 220px;
    }

    .mc-l3-card {
      overflow: hidden;
    }

    .mc-l3-subhead {
      padding: 0.5rem 0.85rem;
      background: var(--mc-color-slate-900);
      color: var(--mc-color-surface);
      font-size: var(--mc-font-size-xs);
      font-weight: 700;
      text-transform: uppercase;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-l3-two-pane {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    .mc-l3-kpi-grid {
      padding: var(--mc-space-3) var(--mc-space-4);
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: var(--mc-space-3);
      border-bottom: var(--mc-border-subtle);
    }

    @media (max-width: 960px) {
      .mc-l3-two-pane,
      .mc-l3-kpi-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-l3-kpi {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .mc-l3-table-wrap {
      overflow-x: auto;
    }

    .mc-l3-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--mc-font-size-sm);

      th {
        padding: 0.45rem 0.75rem;
        background: var(--mc-color-slate-100);
        color: var(--mc-color-slate-700);
        font-size: var(--mc-font-size-xs);
        font-weight: 700;
        text-transform: uppercase;
        text-align: left;
        border-bottom: var(--mc-border-subtle);
      }

      td {
        padding: 0.45rem 0.75rem;
        border-bottom: 1px solid var(--mc-color-slate-100);
        font-weight: 500;
      }
    }

    .mc-l3-row-selected td {
      background: var(--mc-color-teal-50);
      font-weight: 700;
    }

    .mc-l3-total-row td {
      background: var(--mc-color-canvas);
      border-top: var(--mc-border-strong);
      font-weight: 700;
    }

    .mc-col-amt {
      text-align: right;
      width: 150px;
    }

    .mc-l3-period-modal {
      width: min(400px, 92vw);
    }

    .mc-l3-period-fields {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }
  `,
})
export class AccountBooksAndExceptionsComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);
  readonly initialMode = input<TallyLevel3WorkbenchMode | null>(null);
  readonly initialBookType = input<AccountBookType | null>(null);
  readonly initialExceptionType = input<ExceptionReportType | null>(null);

  readonly companyContext = inject(CompanyContextStore);
  readonly coaStore = inject(ChartOfAccountsStore);
  readonly inventoryStore = inject(InventoryStore);
  private readonly reportsService = inject(ReportsService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly bookOptions = BOOK_TYPE_OPTIONS;
  readonly exceptionOptions = EXCEPTION_TYPE_OPTIONS;

  readonly mode = signal<TallyLevel3WorkbenchMode>('ACCOUNT_BOOKS');
  readonly bookType = signal<AccountBookType>('CASH_BANK_SUMMARY');
  readonly exceptionType = signal<ExceptionReportType>('NEGATIVE_STOCK');
  readonly selectedGroupId = signal<string>('');
  readonly selectedLedgerId = signal<string>('');
  readonly selectedStockItemId = signal<string>('');

  readonly fromDate = signal<string>(this.companyContext.periodFrom());
  readonly toDate = signal<string>(this.companyContext.periodTo());
  readonly detailedMode = signal<boolean>(true);
  readonly selectedIndex = signal<number>(0);
  readonly removedRowIds = signal<readonly string[]>([]);
  readonly errorBanner = signal<string | null>(null);

  readonly accountBookReport = signal<AccountBookReportDto | null>(null);
  readonly exceptionReport = signal<ExceptionReportDto | null>(null);
  readonly statisticsReport = signal<StatisticsReportDto | null>(null);
  readonly stockQueryReport = signal<StockQueryReportDto | null>(null);

  readonly periodModalOpen = signal<boolean>(false);
  readonly outputModalOpen = signal<boolean>(false);
  draftFrom = '';
  draftTo = '';
  private periodModalHandle: ActiveModalContextHandle | null = null;

  readonly headingTitle = computed(() => {
    switch (this.mode()) {
      case 'ACCOUNT_BOOKS':
        return (
          BOOK_TYPE_OPTIONS.find((b) => b.value === this.bookType())?.label ?? 'Account Books'
        );
      case 'EXCEPTIONS':
        return (
          EXCEPTION_TYPE_OPTIONS.find((e) => e.value === this.exceptionType())?.label ??
          'Exception Reports'
        );
      case 'STATISTICS':
        return 'Statistics — Types of Vouchers & Masters';
      case 'STOCK_QUERY':
        return 'Stock Query — 360° Item Summary';
    }
  });

  readonly visibleAccountBookRows = computed(() => {
    const hidden = new Set(this.removedRowIds());
    return (this.accountBookReport()?.rows ?? []).filter((r) => !hidden.has(r.id));
  });

  readonly visibleExceptionRows = computed(() => {
    const hidden = new Set(this.removedRowIds());
    return (this.exceptionReport()?.rows ?? []).filter((r) => !hidden.has(r.id));
  });

  constructor() {
    effect(() => {
      const open = this.periodModalOpen();
      this.periodModalHandle?.close();
      this.periodModalHandle = null;
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
              effect: 'Apply selected period bounds.',
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
              effect: 'Close period dialog.',
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

  ngOnInit(): void {
    const dataMode = this.route?.snapshot.data['workbenchMode'] as
      | TallyLevel3WorkbenchMode
      | undefined;
    const qp = this.route?.snapshot.queryParamMap;
    const qpMode = qp?.get('mode') as TallyLevel3WorkbenchMode | null;
    const qpBook = qp?.get('bookType') as AccountBookType | null;
    const qpExc = qp?.get('type') as ExceptionReportType | null;
    const qpGroup = qp?.get('groupId');
    const qpItem = qp?.get('stockItemId');

    this.mode.set(this.initialMode() ?? qpMode ?? dataMode ?? 'ACCOUNT_BOOKS');
    if (this.initialBookType() ?? qpBook) {
      this.bookType.set((this.initialBookType() ?? qpBook)!);
    }
    if (this.initialExceptionType() ?? qpExc) {
      this.exceptionType.set((this.initialExceptionType() ?? qpExc)!);
    }
    if (qpGroup) {
      this.selectedGroupId.set(qpGroup);
    }
    if (qpItem) {
      this.selectedStockItemId.set(qpItem);
    }

    const disposers = [
      this.shortcutRegistry.bindHandler('report.toggleDetailed', 'SCREEN', () => {
        this.detailedMode.update((v) => !v);
      }),
      this.shortcutRegistry.bindHandler('context.changeDate', 'SCREEN', () => {
        this.openPeriodDialog();
      }),
      this.shortcutRegistry.bindHandler('register.removeLine', 'SCREEN', () => {
        this.removeCurrentRow();
      }),
      this.shortcutRegistry.bindHandler('register.restoreLine', 'SCREEN', () => {
        const stack = this.removedRowIds();
        if (stack.length > 0) {
          this.removedRowIds.set(stack.slice(0, -1));
        }
      }),
      this.shortcutRegistry.bindHandler('register.restoreAllLines', 'SCREEN', () => {
        this.removedRowIds.set([]);
      }),
      this.shortcutRegistry.bindHandler('menu.print', 'SCREEN', () => {
        this.outputModalOpen.set(true);
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
      this.periodModalHandle?.close();
    });

    void this.reload();
  }

  @HostListener('document:keydown', ['$event'])
  onDocumentKeyDown(event: KeyboardEvent): void {
    if (this.periodModalOpen() || this.outputModalOpen() || isEditableTextElement(event.target)) {
      return;
    }
    const m = this.mode();
    if (m !== 'ACCOUNT_BOOKS' && m !== 'EXCEPTIONS') {
      return;
    }
    const rows =
      m === 'ACCOUNT_BOOKS' ? this.visibleAccountBookRows() : this.visibleExceptionRows();
    if (rows.length === 0) {
      return;
    }

    if ((event.ctrlKey || event.metaKey) && !event.altKey) {
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        this.selectedIndex.set(0);
        return;
      }
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        this.selectedIndex.set(rows.length - 1);
        return;
      }
    }

    if (event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }

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
      const target = rows[Math.min(Math.max(0, this.selectedIndex()), rows.length - 1)];
      if (target?.voucherId) {
        event.preventDefault();
        this.openVoucherIfPresent(target.voucherId);
      }
    }
  }

  switchMode(next: TallyLevel3WorkbenchMode): void {
    this.mode.set(next);
    this.removedRowIds.set([]);
    this.selectedIndex.set(0);
    void this.reload();
  }

  onBookTypeChange(next: AccountBookType): void {
    this.bookType.set(next);
    this.removedRowIds.set([]);
    void this.reload();
  }

  onGroupChange(groupId: string): void {
    this.selectedGroupId.set(groupId);
    void this.reload();
  }

  onLedgerChange(ledgerId: string): void {
    this.selectedLedgerId.set(ledgerId);
    void this.reload();
  }

  onExceptionTypeChange(next: ExceptionReportType): void {
    this.exceptionType.set(next);
    this.removedRowIds.set([]);
    void this.reload();
  }

  onStockItemChange(itemId: string): void {
    this.selectedStockItemId.set(itemId);
    void this.reload();
  }

  openPeriodDialog(): void {
    this.draftFrom = this.fromDate();
    this.draftTo = this.toDate();
    this.periodModalOpen.set(true);
  }

  applyPeriod(): void {
    if (this.draftFrom && this.draftTo && this.draftFrom <= this.draftTo) {
      this.fromDate.set(this.draftFrom);
      this.toDate.set(this.draftTo);
      this.periodModalOpen.set(false);
      void this.reload();
    }
  }

  removeCurrentRow(): void {
    const rows =
      this.mode() === 'ACCOUNT_BOOKS' ? this.visibleAccountBookRows() : this.visibleExceptionRows();
    const row = rows[this.selectedIndex()];
    if (row) {
      this.removedRowIds.update((prev) => [...prev, row.id]);
    }
  }

  openVoucherIfPresent(voucherId?: string | null): void {
    if (voucherId) {
      void this.router.navigate(['/app/vouchers', voucherId]);
    }
  }

  async exportPdf(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    try {
      await this.reportsService.exportReportPdf(cid, 'trial-balance', {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
      });
    } catch {
      // Non-fatal PDF trigger
    }
  }

  async reload(): Promise<void> {
    const cid = this.companyContext.activeCompany()?.id;
    if (!cid) return;
    this.errorBanner.set(null);

    try {
      const m = this.mode();
      if (m === 'ACCOUNT_BOOKS') {
        const res = await this.reportsService.getAccountBook(cid, {
          bookType: this.bookType(),
          fromDate: this.fromDate(),
          toDate: this.toDate(),
          groupId: this.selectedGroupId() || null,
          ledgerId: this.selectedLedgerId() || null,
        });
        this.accountBookReport.set(res);
      } else if (m === 'EXCEPTIONS') {
        const res = await this.reportsService.getExceptionReport(cid, {
          type: this.exceptionType(),
          fromDate: this.fromDate(),
          toDate: this.toDate(),
          asOfDate: this.toDate(),
        });
        this.exceptionReport.set(res);
      } else if (m === 'STATISTICS') {
        const res = await this.reportsService.getStatistics(cid, {
          fromDate: this.fromDate(),
          toDate: this.toDate(),
        });
        this.statisticsReport.set(res);
      } else {
        await this.inventoryStore.ensureLoaded(cid);
        const chosenId =
          this.selectedStockItemId() || this.inventoryStore.stockItems()[0]?.id || null;
        const res = await this.reportsService.getStockQuery(cid, {
          stockItemId: chosenId,
          fromDate: this.fromDate(),
          toDate: this.toDate(),
        });
        if (res.stockItemId && !this.selectedStockItemId()) {
          this.selectedStockItemId.set(res.stockItemId);
        }
        this.stockQueryReport.set(res);
      }
    } catch (err: unknown) {
      this.errorBanner.set(err instanceof Error ? err.message : 'Unable to load report projection.');
    }
  }
}
