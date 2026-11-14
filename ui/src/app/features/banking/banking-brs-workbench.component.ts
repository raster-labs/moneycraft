import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { ExactDecimal } from '../../core/money/exact-decimal';
import { IndianCurrencyPipe } from '../../core/money/indian-currency.pipe';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { CompanyContextStore } from '../../state/company-context.store';
import { AccountingMastersService } from '../masters/accounting-masters.service';
import { LedgerTypeaheadItem } from '../masters/masters.models';
import {
  BankStatementFormat,
  BankingWorkbenchTab,
  BrsAllocationRowDto,
  BrsSummaryDto,
  ChequeBookDto,
  ChequeLeafDto,
  OcrStagedDocumentDto,
} from './banking.models';
import { BankingService } from './banking.service';

@Component({
  selector: 'mc-banking-brs-workbench',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, IndianCurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bnk-page">
      <header class="mc-card bnk-header">
        <div>
          <span class="bnk-pill">Treasury, Auto-BRS, Cheque Control &amp; Docs-by-Ira OCR</span>
          <h1>Banking &amp; Auto-BRS Workbench</h1>
          <p class="bnk-sub">
            Exact Instrument + Amount + Date-Window Auto-BRS (Alt+M), Cheque Leaf Register &amp;
            Human-Verified OCR Staging
          </p>
        </div>

        <div class="tab-bar">
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'BRS'"
            data-testid="bnk-tab-brs"
            (click)="switchTab('BRS')"
          >
            Auto-BRS Reconciliation
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'CHEQUE_BOOKS'"
            data-testid="bnk-tab-cheques"
            (click)="switchTab('CHEQUE_BOOKS')"
          >
            Cheque Books
          </button>
          <button
            type="button"
            class="mc-btn"
            [class.mc-btn-primary]="activeTab() === 'OCR_QUEUE'"
            data-testid="bnk-tab-ocr"
            (click)="switchTab('OCR_QUEUE')"
          >
            Docs by Ira — OCR Queue
          </button>
        </div>
      </header>

      @if (bannerMessage(); as msg) {
        <div class="bnk-banner" data-testid="bnk-status-banner" role="status">{{ msg }}</div>
      }

      <!-- ================================================================= -->
      <!-- TAB 1: AUTO-BRS BANK RECONCILIATION                               -->
      <!-- ================================================================= -->
      @if (activeTab() === 'BRS') {
        <section class="mc-card toolbar-card">
          <div class="toolbar-controls">
            <label class="ctrl-grp">
              <span>Bank Ledger</span>
              @if (bankLedgers().length > 0) {
                <select
                  class="mc-input"
                  aria-label="Select Bank Ledger"
                  data-testid="brs-bank-select"
                  [ngModel]="selectedBankLedgerId()"
                  (ngModelChange)="onBankChange($event)"
                >
                  @for (bl of bankLedgers(); track bl.id) {
                    <option [value]="bl.id">{{ bl.name }}</option>
                  }
                </select>
              } @else {
                <input
                  type="text"
                  class="mc-input mc-code"
                  aria-label="Bank Ledger ID"
                  data-testid="brs-bank-id-input"
                  [ngModel]="selectedBankLedgerId()"
                  (ngModelChange)="selectedBankLedgerId.set($event)"
                  placeholder="led-hdfc-bank"
                />
              }
            </label>

            <label class="ctrl-grp">
              <span>Reconcile As Of</span>
              <input
                type="date"
                class="mc-input"
                aria-label="BRS As Of Date"
                [ngModel]="asOfDate()"
                (ngModelChange)="asOfDate.set($event)"
              />
            </label>

            <label class="ctrl-grp">
              <span>Window (Days)</span>
              <input
                type="number"
                min="0"
                max="30"
                class="mc-input window-input"
                aria-label="Auto Match Date Window Days"
                [ngModel]="dateWindowDays()"
                (ngModelChange)="dateWindowDays.set(+$event)"
              />
            </label>

            <button
              type="button"
              class="mc-btn"
              data-testid="brs-load-btn"
              (click)="loadBrs()"
            >
              Load BRS
            </button>
            <button
              type="button"
              class="mc-btn"
              data-testid="brs-open-import-btn"
              (click)="importModalOpen.set(true)"
            >
              Import Statement (CSV / MT940 / CAMT.053)
            </button>
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              data-testid="brs-auto-match-btn"
              (click)="runAutoMatch()"
            >
              Auto-BRS Match (Alt+M)
            </button>
          </div>
        </section>

        @if (brsSummary(); as brs) {
          <div class="brs-split">
            <!-- Unreconciled Company Book Allocations -->
            <section class="mc-card panel-card">
              <header class="panel-head">
                <h2>
                  Unreconciled Book Allocations ({{ brs.unreconciledAllocations.length }})
                </h2>
              </header>
              <table class="mc-table" data-testid="brs-unreconciled-table">
                <thead>
                  <tr>
                    <th>Vch Date / No</th>
                    <th>Instrument</th>
                    <th>Particulars</th>
                    <th class="num-col">Dr (Receipt)</th>
                    <th class="num-col">Cr (Payment)</th>
                    <th>Bankers Date (Manual)</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of brs.unreconciledAllocations; track row.bankAllocationId) {
                    <tr>
                      <td>
                        <div>{{ row.voucherDate }}</div>
                        <mc-code-badge [code]="row.voucherNumber" />
                      </td>
                      <td>
                        @if (row.instrumentNo) {
                          <mc-code-badge [code]="row.instrumentNo" />
                        } @else {
                          <span>{{ row.transactionType }}</span>
                        }
                      </td>
                      <td>{{ row.partyOrNarration }}</td>
                      <td class="mc-amount num-col">{{ row.debitAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ row.creditAmount | mcIndianCurrency }}</td>
                      <td>
                        <div class="inline-reconcile">
                          <input
                            type="date"
                            class="mc-input"
                            [attr.data-testid]="'brs-bankers-date-' + row.bankAllocationId"
                            [ngModel]="bankersDateFor(row)"
                            (ngModelChange)="setBankersDate(row.bankAllocationId, $event)"
                            aria-label="Bankers Date"
                          />
                          <button
                            type="button"
                            class="mc-btn mc-btn-primary"
                            [attr.data-testid]="'brs-reconcile-btn-' + row.bankAllocationId"
                            (click)="manualReconcile(row)"
                          >
                            Set
                          </button>
                        </div>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="empty-cell">
                        All company book bank entries are reconciled!
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </section>

            <!-- Statement Lines & Auto-Matched Pairs -->
            <section class="mc-card panel-card">
              <header class="panel-head">
                <h2>
                  Unmatched Bank Statement Lines ({{ brs.unmatchedStatementLines.length }}) &amp;
                  Matched ({{ brs.matches.length }})
                </h2>
              </header>
              <table class="mc-table" data-testid="brs-statement-table">
                <thead>
                  <tr>
                    <th>Value Date</th>
                    <th>Inst. No</th>
                    <th>Bank Narration</th>
                    <th class="num-col">Withdrawal</th>
                    <th class="num-col">Deposit</th>
                  </tr>
                </thead>
                <tbody>
                  @for (st of brs.unmatchedStatementLines; track st.id) {
                    <tr>
                      <td>{{ st.valueDate }}</td>
                      <td>
                        @if (st.instrumentNo) {
                          <mc-code-badge [code]="st.instrumentNo" />
                        } @else {
                          <span>—</span>
                        }
                      </td>
                      <td>{{ st.description }}</td>
                      <td class="mc-amount num-col">{{ st.debitAmount | mcIndianCurrency }}</td>
                      <td class="mc-amount num-col">{{ st.creditAmount | mcIndianCurrency }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="5" class="empty-cell">
                        Zero unmatched bank statement lines remaining.
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </section>
          </div>

          <!-- Authentic Tally BRS Reconciliation Footer -->
          <footer class="mc-card brs-recon-footer" data-testid="brs-recon-footer">
            <div class="recon-grid">
              <div class="recon-row">
                <span>Balance as per Company Books:</span>
                <strong class="mc-amount" data-testid="brs-company-books-bal">
                  {{ brs.balanceAsPerCompanyBooks | mcIndianCurrency }}
                </strong>
              </div>
              <div class="recon-row">
                <span>Amounts not reflected in Bank (Dr / Cr):</span>
                <span class="mc-amount">
                  {{ brs.amountsNotReflectedInBankDr | mcIndianCurrency }} Dr /
                  {{ brs.amountsNotReflectedInBankCr | mcIndianCurrency }} Cr
                </span>
              </div>
              <div class="recon-row">
                <span>Balance as per Bank (Computed):</span>
                <strong class="mc-amount" data-testid="brs-computed-bank-bal">
                  {{ brs.balanceAsPerBankComputed | mcIndianCurrency }}
                </strong>
              </div>
              <div class="recon-row">
                <span>Statement Closing Balance:</span>
                <strong class="mc-amount" data-testid="brs-statement-closing-bal">
                  {{ brs.statementClosingBalance | mcIndianCurrency }}
                </strong>
                @if (brsDifferenceWire() === '0.00') {
                  <span class="mc-badge mc-badge-jade" data-testid="brs-reconciled-badge">
                    Reconciled (0.00 Diff)
                  </span>
                }
              </div>
            </div>
          </footer>
        }
      }

      <!-- ================================================================= -->
      <!-- TAB 2: CHEQUE BOOKS & LEAF REGISTER                               -->
      <!-- ================================================================= -->
      @if (activeTab() === 'CHEQUE_BOOKS') {
        <div class="brs-split">
          <section class="mc-card panel-card">
            <header class="panel-head">
              <h2>Create Cheque Book Range</h2>
            </header>
            <form class="form-pad" (ngSubmit)="createChequeBook()">
              <label class="ctrl-grp">
                <span>Bank Ledger ID</span>
                <input
                  type="text"
                  name="cbBankId"
                  class="mc-input mc-code"
                  data-testid="cb-bank-id-input"
                  [(ngModel)]="cbBankLedgerId"
                  required
                />
              </label>
              <label class="ctrl-grp">
                <span>Cheque Book Name</span>
                <input
                  type="text"
                  name="cbName"
                  class="mc-input"
                  data-testid="cb-name-input"
                  [(ngModel)]="cbBookName"
                  placeholder="HDFC Main Series 2025"
                  required
                />
              </label>
              <div class="two-inline">
                <label class="ctrl-grp">
                  <span>From Leaf No (6-digit)</span>
                  <input
                    type="text"
                    name="cbFrom"
                    class="mc-input mc-code"
                    data-testid="cb-from-input"
                    [(ngModel)]="cbFromLeaf"
                    required
                  />
                </label>
                <label class="ctrl-grp">
                  <span>To Leaf No (6-digit)</span>
                  <input
                    type="text"
                    name="cbTo"
                    class="mc-input mc-code"
                    data-testid="cb-to-input"
                    [(ngModel)]="cbToLeaf"
                    required
                  />
                </label>
              </div>
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="cb-create-btn"
              >
                Save Cheque Book
              </button>
            </form>
          </section>

          <section class="mc-card panel-card">
            <header class="panel-head">
              <h2>Cheque Books &amp; Leaf Status</h2>
            </header>
            <table class="mc-table" data-testid="cheque-books-table">
              <thead>
                <tr>
                  <th>Book Name</th>
                  <th>Range</th>
                  <th>Available / Total</th>
                  <th>Leaves</th>
                </tr>
              </thead>
              <tbody>
                @for (bk of chequeBooks(); track bk.id) {
                  <tr>
                    <td><strong>{{ bk.bookName }}</strong></td>
                    <td>
                      <mc-code-badge [code]="bk.fromLeafNo + ' .. ' + bk.toLeafNo" />
                    </td>
                    <td class="mc-amount">{{ bk.availableLeaves }} / {{ bk.totalLeaves }}</td>
                    <td>
                      <button
                        type="button"
                        class="mc-btn"
                        (click)="inspectLeaves(bk.id)"
                      >
                        View Leaves
                      </button>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="4" class="empty-cell">No cheque books registered yet.</td>
                  </tr>
                }
              </tbody>
            </table>

            @if (selectedLeaves().length > 0) {
              <div class="leaves-grid" data-testid="cheque-leaves-list">
                @for (lf of selectedLeaves(); track lf.id) {
                  <div class="leaf-chip">
                    <mc-code-badge [code]="lf.leafNumber" />
                    <span>{{ lf.status }}</span>
                  </div>
                }
              </div>
            }
          </section>
        </div>
      }

      <!-- ================================================================= -->
      <!-- TAB 3: DOCS BY IRA — OCR STAGING QUEUE                            -->
      <!-- ================================================================= -->
      @if (activeTab() === 'OCR_QUEUE') {
        <section class="mc-card panel-card">
          <header class="panel-head">
            <h2>Docs by Ira — Human-Verified OCR Invoice Staging Queue</h2>
            <button
              type="button"
              class="mc-btn"
              data-testid="ocr-stage-sample-btn"
              (click)="stageNewOcrDocModal.set(true)"
            >
              + Stage Extracted Document
            </button>
          </header>

          <table class="mc-table" data-testid="ocr-documents-table">
            <thead>
              <tr>
                <th>File / Type</th>
                <th>Supplier &amp; GSTIN</th>
                <th>Invoice No &amp; Date</th>
                <th class="num-col">Total Amount</th>
                <th class="num-col">Confidence</th>
                <th>Status / Action</th>
              </tr>
            </thead>
            <tbody>
              @for (doc of ocrDocs(); track doc.id) {
                <tr>
                  <td>
                    <strong>{{ doc.fileName }}</strong>
                    <div class="muted-small">{{ doc.documentType }}</div>
                  </td>
                  <td>
                    <div>{{ doc.supplierName }}</div>
                    @if (doc.supplierGstin) {
                      <mc-code-badge [code]="doc.supplierGstin" />
                    }
                  </td>
                  <td>
                    <mc-code-badge [code]="doc.invoiceNumber" />
                    <div class="muted-small">{{ doc.invoiceDate }}</div>
                  </td>
                  <td class="mc-amount num-col">{{ doc.totalAmount | mcIndianCurrency }}</td>
                  <td class="mc-amount num-col">{{ doc.confidencePct }}%</td>
                  <td>
                    @if (doc.reviewStatus === 'APPROVED_CONVERTED') {
                      <span class="mc-badge mc-badge-jade">
                        Converted
                        @if (doc.convertedVoucherId) {
                          ({{ doc.convertedVoucherId }})
                        }
                      </span>
                    } @else {
                      <button
                        type="button"
                        class="mc-btn mc-btn-primary"
                        [attr.data-testid]="'ocr-approve-btn-' + doc.id"
                        (click)="approveAndConvertOcr(doc)"
                      >
                        Approve &amp; Convert to Draft
                      </button>
                    }
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="6" class="empty-cell">No staged OCR documents pending human review.</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }

      <!-- Statement Import Modal -->
      @if (importModalOpen()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Import Bank Statement"
          (keydown.escape)="importModalOpen.set(false)"
        >
          <form class="mc-modal-dialog import-dialog" (ngSubmit)="submitStatementImport()">
            <div class="mc-modal-header">
              <span>Import Electronic Bank Statement (CSV / MT940 / CAMT.053)</span>
              <button type="button" class="mc-btn" (click)="importModalOpen.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body form-pad">
              <div class="two-inline">
                <label class="ctrl-grp">
                  <span>Format</span>
                  <select
                    name="stmtFormat"
                    class="mc-input"
                    data-testid="stmt-format-select"
                    [(ngModel)]="stmtFormat"
                  >
                    <option value="CSV">CSV Statement</option>
                    <option value="MT940">SWIFT MT940</option>
                    <option value="CAMT_053">ISO 20022 CAMT.053</option>
                  </select>
                </label>
                <label class="ctrl-grp">
                  <span>File Name</span>
                  <input
                    type="text"
                    name="stmtFileName"
                    class="mc-input mc-code"
                    [(ngModel)]="stmtFileName"
                    required
                  />
                </label>
              </div>
              <label class="ctrl-grp">
                <span>Statement Content</span>
                <textarea
                  name="stmtRawContent"
                  rows="5"
                  class="mc-input mc-code"
                  data-testid="stmt-raw-textarea"
                  [(ngModel)]="stmtRawContent"
                  placeholder="2025-04-12,CHQ-400101,Supplier Settlement,25000.00,0.00"
                  required
                ></textarea>
              </label>
            </div>
            <div class="mc-modal-footer">
              <button
                type="submit"
                class="mc-btn mc-btn-primary"
                data-testid="stmt-import-submit-btn"
              >
                Import Statement
              </button>
            </div>
          </form>
        </div>
      }

      <!-- Stage New OCR Document Modal -->
      @if (stageNewOcrDocModal()) {
        <div
          class="mc-modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-label="Stage OCR Document"
          (keydown.escape)="stageNewOcrDocModal.set(false)"
        >
          <form class="mc-modal-dialog import-dialog" (ngSubmit)="submitNewOcrDoc()">
            <div class="mc-modal-header">
              <span>Stage Extracted Invoice for Review</span>
              <button type="button" class="mc-btn" (click)="stageNewOcrDocModal.set(false)">Esc</button>
            </div>
            <div class="mc-modal-body form-pad">
              <label class="ctrl-grp">
                <span>Supplier Name</span>
                <input type="text" name="ocrSupplier" class="mc-input" [(ngModel)]="ocrSupplier" required />
              </label>
              <div class="two-inline">
                <label class="ctrl-grp">
                  <span>Supplier GSTIN</span>
                  <input type="text" name="ocrGstin" class="mc-input mc-code" [(ngModel)]="ocrGstin" />
                </label>
                <label class="ctrl-grp">
                  <span>Invoice No</span>
                  <input type="text" name="ocrInvNo" class="mc-input mc-code" [(ngModel)]="ocrInvNo" required />
                </label>
              </div>
              <div class="two-inline">
                <label class="ctrl-grp">
                  <span>Invoice Date</span>
                  <input type="date" name="ocrInvDate" class="mc-input" [(ngModel)]="ocrInvDate" required />
                </label>
                <label class="ctrl-grp">
                  <span>Total Amount</span>
                  <input type="text" name="ocrTotal" class="mc-input mc-amount" [(ngModel)]="ocrTotal" required />
                </label>
              </div>
            </div>
            <div class="mc-modal-footer">
              <button type="submit" class="mc-btn mc-btn-primary" data-testid="ocr-stage-submit-btn">
                Stage Document
              </button>
            </div>
          </form>
        </div>
      }

      <footer class="mc-card nav-footer">
        <a routerLink="/app/reports/cash-flow" class="mc-btn">Cash Flow Statement</a>
        <a routerLink="/app/reports/balance-sheet" class="mc-btn">Balance Sheet</a>
      </footer>
    </div>
  `,
  styles: `
    .bnk-page {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
    }

    .bnk-header {
      padding: 0.85rem 1rem;
      background: #142f42;
      color: #ffffff;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    .bnk-header h1 {
      margin: 0.15rem 0 0.1rem;
      font-size: 1.12rem;
      font-weight: 700;
    }

    .bnk-sub {
      margin: 0;
      font-size: 0.78rem;
      color: #cbd5e1;
      font-weight: 400;
    }

    .bnk-pill {
      font-size: 0.68rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #4ce1bd;
    }

    .tab-bar,
    .toolbar-controls {
      display: flex;
      align-items: flex-end;
      flex-wrap: wrap;
      gap: 0.45rem;
    }

    .toolbar-card {
      padding: 0.75rem 1rem;
    }

    .ctrl-grp {
      display: flex;
      flex-direction: column;
      gap: 3px;
      font-size: 0.74rem;
      font-weight: 600;
      color: #334155;
    }

    .window-input {
      width: 78px;
    }

    .bnk-banner {
      padding: 0.5rem 0.85rem;
      background: #ecfdf5;
      border: 1px solid #6ee7b7;
      color: #065f46;
      border-radius: 6px;
      font-size: 0.8rem;
      font-weight: 600;
    }

    .brs-split {
      display: grid;
      grid-template-columns: 1.25fr 1fr;
      gap: 0.75rem;
    }

    @media (max-width: 980px) {
      .brs-split {
        grid-template-columns: 1fr;
      }
    }

    .panel-card {
      overflow: hidden;
    }

    .panel-head {
      padding: 0.6rem 0.95rem;
      background: #f6faf9;
      border-bottom: 1px solid #cbd5e1;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .panel-head h2 {
      margin: 0;
      font-size: 0.9rem;
      font-weight: 700;
      color: #142f42;
    }

    .num-col {
      text-align: right;
    }

    .inline-reconcile {
      display: flex;
      align-items: center;
      gap: 0.35rem;
    }

    .brs-recon-footer {
      padding: 0.8rem 1rem;
      background: #f6faf9;
      border: 2px solid #142f42;
    }

    .recon-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 0.65rem;
    }

    .recon-row {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
      font-size: 0.8rem;
      font-weight: 500;
      color: #334155;
    }

    .form-pad {
      padding: 0.85rem 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.65rem;
    }

    .two-inline {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.55rem;
    }

    .leaves-grid {
      padding: 0.75rem 1rem;
      border-top: 1px solid #e2e8f0;
      display: flex;
      flex-wrap: wrap;
      gap: 0.45rem;
    }

    .leaf-chip {
      padding: 0.25rem 0.5rem;
      border: 1px solid #cbd5e1;
      border-radius: 4px;
      display: flex;
      align-items: center;
      gap: 0.35rem;
      font-size: 0.74rem;
      font-weight: 600;
    }

    .muted-small {
      font-size: 0.72rem;
      color: #64748b;
    }

    .empty-cell {
      padding: 1.4rem;
      text-align: center;
      color: #64748b;
      font-weight: 500;
    }

    .import-dialog {
      width: min(520px, 94vw);
    }

    .nav-footer {
      padding: 0.6rem 1rem;
      display: flex;
      gap: 0.45rem;
    }
  `,
})
export class BankingBrsWorkbenchComponent implements OnInit {
  readonly embeddedOverlay = input<boolean>(false);
  readonly initialTab = input<BankingWorkbenchTab | null>(null);
  readonly initialBankLedgerId = input<string | null>(null);

  private readonly companyContext = inject(CompanyContextStore);
  private readonly bankingService = inject(BankingService);
  private readonly mastersService = inject(AccountingMastersService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  readonly activeTab = signal<BankingWorkbenchTab>('BRS');
  readonly bankLedgers = signal<readonly LedgerTypeaheadItem[]>([]);
  readonly selectedBankLedgerId = signal<string>('');
  readonly asOfDate = signal<string>(this.companyContext.periodTo());
  readonly dateWindowDays = signal<number>(3);
  readonly lastStatementImportId = signal<string | null>(null);

  readonly brsSummary = signal<BrsSummaryDto | null>(null);
  readonly draftBankersDates = signal<Readonly<Record<string, string>>>({});
  readonly chequeBooks = signal<readonly ChequeBookDto[]>([]);
  readonly selectedLeaves = signal<readonly ChequeLeafDto[]>([]);
  readonly ocrDocs = signal<readonly OcrStagedDocumentDto[]>([]);

  readonly bannerMessage = signal<string | null>(null);
  readonly importModalOpen = signal<boolean>(false);
  readonly stageNewOcrDocModal = signal<boolean>(false);

  stmtFormat: BankStatementFormat = 'CSV';
  stmtFileName = 'hdfc_april_2025.csv';
  stmtRawContent = '';

  cbBankLedgerId = '';
  cbBookName = '';
  cbFromLeaf = '400101';
  cbToLeaf = '400150';

  ocrSupplier = 'Kaveri Industrial Supplies';
  ocrGstin = '33AABCK1111F1Z9';
  ocrInvNo = 'KIS/25-26/088';
  ocrInvDate = '2025-04-20';
  ocrTotal = '11800.00';

  readonly brsDifferenceWire = computed(() => {
    const s = this.brsSummary();
    if (!s) return '0.00';
    const comp = ExactDecimal.tryParse(s.balanceAsPerBankComputed, 2) ?? ExactDecimal.zero(2);
    const stmt = ExactDecimal.tryParse(s.statementClosingBalance, 2) ?? ExactDecimal.zero(2);
    return comp.sub(stmt, 2).abs().toWireString(2);
  });

  ngOnInit(): void {
    const tabInput = this.initialTab();
    const tabRoute = this.route?.snapshot.data['bankingTab'] as BankingWorkbenchTab | undefined;
    if (tabInput) {
      this.activeTab.set(tabInput);
    } else if (tabRoute) {
      this.activeTab.set(tabRoute);
    }

    const bankInit = this.initialBankLedgerId();
    if (bankInit) {
      this.selectedBankLedgerId.set(bankInit);
      this.cbBankLedgerId = bankInit;
    }

    const disposeAutoMatch = this.shortcutRegistry.bindHandler('banking.autoMatch', 'SCREEN', () => {
      if (this.activeTab() === 'BRS') {
        void this.runAutoMatch();
      }
    });
    this.destroyRef.onDestroy(() => disposeAutoMatch());

    void this.initializeTab();
  }

  private async initializeTab(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;

    if (this.activeTab() === 'BRS') {
      if (!this.selectedBankLedgerId()) {
        try {
          const ledgers = await this.mastersService.searchLedgerTypeahead(
            companyId,
            '',
            'CASH_BANK',
          );
          this.bankLedgers.set(ledgers);
          if (ledgers.length > 0) {
            this.selectedBankLedgerId.set(ledgers[0].id);
            this.cbBankLedgerId = ledgers[0].id;
          }
        } catch {
          this.bankLedgers.set([]);
        }
      }
      if (this.selectedBankLedgerId()) {
        await this.loadBrs();
      }
    } else if (this.activeTab() === 'CHEQUE_BOOKS') {
      await this.loadChequeBooks();
    } else if (this.activeTab() === 'OCR_QUEUE') {
      await this.loadOcrDocs();
    }
  }

  switchTab(next: BankingWorkbenchTab): void {
    this.activeTab.set(next);
    void this.initializeTab();
  }

  onBankChange(bankId: string): void {
    this.selectedBankLedgerId.set(bankId);
    this.cbBankLedgerId = bankId;
    void this.loadBrs();
  }

  async loadBrs(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const bankId = this.selectedBankLedgerId().trim();
    if (!companyId || !bankId) return;
    try {
      const summary = await this.bankingService.getBrsSummary(companyId, bankId, this.asOfDate());
      this.brsSummary.set(summary);
    } catch {
      this.brsSummary.set(null);
    }
  }

  bankersDateFor(row: BrsAllocationRowDto): string {
    return this.draftBankersDates()[row.bankAllocationId] ?? row.bankersDate ?? row.voucherDate;
  }

  setBankersDate(bankAllocationId: string, dateVal: string): void {
    this.draftBankersDates.set({
      ...this.draftBankersDates(),
      [bankAllocationId]: dateVal,
    });
  }

  async manualReconcile(row: BrsAllocationRowDto): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    const bankersDate = this.bankersDateFor(row);
    if (!bankersDate) return;

    await this.bankingService.reconcileAllocation(companyId, {
      bankAllocationId: row.bankAllocationId,
      bankersDate,
      statementLineId: null,
    });

    const cur = this.brsSummary();
    if (cur) {
      this.brsSummary.set({
        ...cur,
        unreconciledAllocations: cur.unreconciledAllocations.filter(
          (u) => u.bankAllocationId !== row.bankAllocationId,
        ),
      });
    }
    this.bannerMessage.set(`Reconciled voucher #${row.voucherNumber} with Bankers Date ${bankersDate}.`);
  }

  async submitStatementImport(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const bankId = this.selectedBankLedgerId().trim();
    if (!companyId || !bankId || !this.stmtRawContent.trim()) return;

    const res = await this.bankingService.importBankStatement(companyId, {
      bankLedgerId: bankId,
      fileName: this.stmtFileName,
      sourceFormat: this.stmtFormat,
      rawContent: this.stmtRawContent,
    });
    this.lastStatementImportId.set(res.statementImportId);
    this.importModalOpen.set(false);
    this.bannerMessage.set(
      `Imported ${res.importedLinesCount} statement lines (${res.sourceFormat}). Press Alt+M for Auto-BRS.`,
    );
    await this.loadBrs();
  }

  async runAutoMatch(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    const bankId = this.selectedBankLedgerId().trim();
    if (!companyId || !bankId) return;

    const res = await this.bankingService.autoMatchBrs(companyId, {
      bankLedgerId: bankId,
      statementImportId: this.lastStatementImportId(),
      dateWindowDays: this.dateWindowDays(),
    });
    if (res.summary) {
      this.brsSummary.set(res.summary);
    } else {
      await this.loadBrs();
    }
    this.bannerMessage.set(
      `Auto-BRS matched ${res.matchedCount} entries (${res.remainingUnreconciledCount} remaining).`,
    );
  }

  async loadChequeBooks(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    try {
      this.chequeBooks.set(await this.bankingService.listChequeBooks(companyId));
    } catch {
      this.chequeBooks.set([]);
    }
  }

  async createChequeBook(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId || !this.cbBankLedgerId.trim() || !this.cbBookName.trim()) return;
    const created = await this.bankingService.createChequeBook(companyId, {
      bankLedgerId: this.cbBankLedgerId.trim(),
      bookName: this.cbBookName.trim(),
      fromLeafNo: this.cbFromLeaf.trim(),
      toLeafNo: this.cbToLeaf.trim(),
    });
    this.chequeBooks.set([...this.chequeBooks(), created]);
    this.bannerMessage.set(`Created Cheque Book "${created.bookName}".`);
  }

  async inspectLeaves(bookId: string): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    this.selectedLeaves.set(await this.bankingService.listChequeLeaves(companyId, bookId));
  }

  async loadOcrDocs(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    try {
      this.ocrDocs.set(await this.bankingService.listOcrDocuments(companyId));
    } catch {
      this.ocrDocs.set([]);
    }
  }

  async submitNewOcrDoc(): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    const doc = await this.bankingService.createOcrDocument(companyId, {
      fileName: `${this.ocrInvNo.replace(/[^a-zA-Z0-9]/g, '_')}.pdf`,
      documentType: 'PURCHASE_INVOICE',
      supplierName: this.ocrSupplier,
      supplierGstin: this.ocrGstin || null,
      invoiceNumber: this.ocrInvNo,
      invoiceDate: this.ocrInvDate,
      taxableAmount: this.ocrTotal,
      taxAmount: '0.00',
      totalAmount: this.ocrTotal,
    });
    this.ocrDocs.set([doc, ...this.ocrDocs()]);
    this.stageNewOcrDocModal.set(false);
    this.bannerMessage.set(`Staged OCR invoice ${doc.invoiceNumber} for human verification.`);
  }

  async approveAndConvertOcr(doc: OcrStagedDocumentDto): Promise<void> {
    const companyId = this.companyContext.activeCompany()?.id;
    if (!companyId) return;
    const res = await this.bankingService.approveConvertOcrDocument(companyId, doc.id, {
      voucherTypeName: 'Purchase',
      verifiedTotalAmount: doc.totalAmount,
      narration: `Human-verified OCR import of ${doc.invoiceNumber}`,
    });
    this.ocrDocs.set(
      this.ocrDocs().map((d) =>
        d.id === doc.id
          ? {
              ...d,
              reviewStatus: 'APPROVED_CONVERTED',
              convertedVoucherId: res.convertedVoucherId,
            }
          : d,
      ),
    );
    this.bannerMessage.set(
      `Approved OCR document ${doc.invoiceNumber} -> Draft Voucher ${res.convertedVoucherId}.`,
    );
  }
}
